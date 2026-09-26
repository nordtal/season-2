package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AdminTree;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.discordbot.access.discord.ReconcileDao;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jdbi.v3.core.Jdbi;

/**
 * Keeps {@code discord_user.member_state} and {@code locale} current.
 *
 * The proxy decides whether a login is allowed and cannot ask Discord anything, so these are projections the bot
 * maintains: from gateway events while it runs, and from one reconcile at startup.
 *
 * Language is mirrored from Discord roles the bot never assigns. Admin is not: it is a grant tree decided in
 * Steward, and the admin role follows it, not the other way round - see {@link AdminRole}. What this class still
 * does about admin is the one exit from the tree that is not a revocation: leaving or being banned from the guild
 * drops that admin and their whole branch, because a stale {@code true} would let somebody through
 * {@code MAINTENANCE} and switch the season phase.
 *
 * Leaving the guild removes the account link. Nothing is lost with it: play time, aura and grants hang off
 * {@code discord_user}, so re-linking the same account restores them. A ban is a removal too - Discord sends the
 * remove event either way.
 *
 * The startup reconcile deletes links only when it can see the whole guild: writing {@code LEFT} is repaired by the
 * next pass, deleting a link is not, and an incompletely chunked member cache would take every link in the guild
 * with it.
 *
 * A ban does not pause anything - {@code BANNED} refuses the login while the paid period keeps running down. This
 * class writes state and never touches a grant.
 *
 * It also mirrors a name and a face: the global username and the effective nickname and avatar, written wherever
 * this class already visits a member - the join event and the reconcile pass - never in a loop of its own. Leaving
 * or being banned clears the guild-scoped half of that (nickname, avatar) the same way it drops {@code admin}; the
 * username is left as last observed, because it is not guild-scoped and merely goes stale rather than becoming
 * wrong. See {@link eu.nordtal.s2.common.access.DiscordProfile} .
 */
@Slf4j
public final class GuildState extends ListenerAdapter {

    private final JDA jda;
    private final AccessSpec config;
    private final Languages languages;
    private final AccessDirectory access;
    private final AdminTree admins;
    private final ReconcileDao dao;

    public GuildState(
            final JDA jda,
            final AccessSpec config,
            final Languages languages,
            final AccessDirectory access,
            final AdminTree admins,
            final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
        this.languages = languages;
        this.access = access;
        this.admins = admins;
        this.dao = jdbi.onDemand(ReconcileDao.class);
    }

    // Events.

    @Override
    public void onGuildMemberJoin(final GuildMemberJoinEvent event) {
        if (!ours(event.getGuild()) || event.getMember().getUser().isBot()) {
            return;
        }
        access.setMemberState(event.getMember().getId(), MemberState.MEMBER);
        mirrorLocale(event.getMember());
        mirrorProfile(event.getMember());
    }

    @Override
    public void onGuildMemberRemove(final GuildMemberRemoveEvent event) {
        if (!ours(event.getGuild()) || event.getUser().isBot()) {
            return;
        }
        // A ban also produces a remove; GuildBanEvent overwrites this with BANNED, in an order reconcile re-derives.
        access.setMemberState(event.getUser().getId(), MemberState.LEFT);
        // Somebody not in the guild is not an admin, nor is anybody they granted; else the flag survives a removal.
        dropAdmin(event.getUser().getId());
        // Nor a guild nickname or avatar - both scoped to a guild this account left. See clearGuildProfile.
        access.clearGuildProfile(event.getUser().getId());
        // And not a linked member either. Safe here unlike in reconcile(): Discord named this one user directly.
        if (access.unlink(event.getUser().getId())) {
            log.info(
                    "{} left the guild; their Minecraft account link was removed",
                    event.getUser().getId());
        }
    }

    @Override
    public void onGuildBan(final GuildBanEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        access.setMemberState(event.getUser().getId(), MemberState.BANNED);
        dropAdmin(event.getUser().getId());
        access.clearGuildProfile(event.getUser().getId());
    }

    @Override
    public void onGuildUnban(final GuildUnbanEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        // Unbanning does not put anybody back in the guild - rejoining produces its own event. LEFT, not MEMBER.
        access.setMemberState(event.getUser().getId(), MemberState.LEFT);
    }

    @Override
    public void onGuildMemberRoleAdd(final GuildMemberRoleAddEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        if (touchesLanguage(event.getRoles())) {
            mirrorLocale(event.getMember());
        }
    }

    @Override
    public void onGuildMemberRoleRemove(final GuildMemberRoleRemoveEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        if (touchesLanguage(event.getRoles())) {
            mirrorLocale(event.getMember());
        }
    }

    // Startup.

    /**
     * Catches up on everything that happened while the bot was down, in three passes.
     *
     * Everybody in the guild is a {@code MEMBER}, everybody on the ban list is {@code BANNED}, and everybody we know
     * about who is in neither has {@code LEFT} - the one no event could deliver.
     *
     * The last two passes also drop admins, with their branches: leaving while the bot was down produces no event. The
     * third does so only on a complete picture, like the unlink.
     *
     * The third pass deletes the account link only when the picture is complete - see
     * {@link #memberCacheLooksComplete(int, int)}. That is the one place where being wrong is not repaired by the next
     * run.
     */
    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; guild state was not reconciled", config.guildId());
            return;
        }

        // One snapshot for both the pass and the completeness decision, so a mid-read join cannot fool the count.
        final MemberSweep sweep = sweepMembers(guild);
        final boolean banListRead = sweepBanList(guild, sweep.seen());

        // Read after the snapshot: a mid-pass join raises the count but not the snapshot, so the check fails safely.
        final int expected = guild.getMemberCount();
        final boolean mayUnlink = memberCacheLooksComplete(sweep.cached(), expected) && banListRead;

        final Removal removal = dropStaleAccounts(sweep.seen(), mayUnlink);

        log.info(
                "Reconciled guild state: {} member(s), {} known account(s) no longer present," + " {} link(s) removed",
                sweep.seen().size(),
                removal.left(),
                removal.unlinked());
        if (!mayUnlink && removal.left() > 0) {
            log.warn(
                    "Account links and admin grants were left in place for those {} account(s): the member cache"
                            + " holds {} of {} member(s) and the ban list {} read. Deleting on an"
                            + " incomplete picture would unlink the whole guild; the next reconcile that"
                            + " sees everything will do it.",
                    removal.left(),
                    sweep.cached(),
                    expected,
                    banListRead ? "was" : "was not");
        }
    }

    /** What one pass over the member cache found: everybody seen, and how many the snapshot held in total. */
    private record MemberSweep(Set<String> seen, int cached) {}

    /** How many known accounts turned out to be gone, and how many of those had a link removed with them. */
    private record Removal(int left, int unlinked) {}

    private MemberSweep sweepMembers(final Guild guild) {
        final Set<String> seen = new HashSet<>();
        final List<Member> members = guild.getMemberCache().asList();
        for (final Member member : members) {
            if (member.getUser().isBot()) {
                continue;
            }
            access.setMemberState(member.getId(), MemberState.MEMBER);
            mirrorLocale(member);
            mirrorProfile(member);
            seen.add(member.getId());
        }
        return new MemberSweep(seen, members.size());
    }

    private boolean sweepBanList(final Guild guild, final Set<String> seen) {
        try {
            guild.retrieveBanList().stream().forEach(ban -> {
                access.setMemberState(ban.getUser().getId(), MemberState.BANNED);
                dropAdmin(ban.getUser().getId());
                access.clearGuildProfile(ban.getUser().getId());
                seen.add(ban.getUser().getId());
            });
            return true;
        } catch (final RuntimeException exception) {
            log.error("Could not read the ban list; banned users may still be marked as members", exception);
            return false;
        }
    }

    private Removal dropStaleAccounts(final Set<String> seen, final boolean mayUnlink) {
        int left = 0;
        int unlinked = 0;
        for (final String discordId : dao.allUsers()) {
            if (!seen.contains(discordId)) {
                access.setMemberState(discordId, MemberState.LEFT);
                access.clearGuildProfile(discordId);
                left++;
                // Dropping an admin is as final as deleting a link, so it waits for the same complete picture.
                if (mayUnlink) {
                    dropAdmin(discordId);
                    if (access.unlink(discordId)) {
                        unlinked++;
                    }
                }
            }
        }
        return new Removal(left, unlinked);
    }

    /**
     * Whether the member cache can be trusted to answer "who is in this guild".
     *
     * The only thing standing between an unlucky startup and every account link in the database, which is why it
     * is static and testable without a guild. Greater-than-or-equal rather than equal: somebody who
     * leaves mid-pass is still in the snapshot and therefore in {@code seen} anyway.
     *
     * @param cached   how many members the snapshot holds
     * @param expected how many the guild says it has; {@code 0} or less means Discord has not told
     *                 us, which is not an answer and is treated as "cannot tell"
     * @return whether links may be deleted on the strength of this cache
     */
    static boolean memberCacheLooksComplete(final int cached, final int expected) {
        return expected > 0 && cached >= expected;
    }

    // Helpers.

    private boolean ours(final Guild guild) {
        return config.guildId().equals(guild.getId());
    }

    private boolean touchesLanguage(final List<Role> changed) {
        return changed.stream().anyMatch(role -> languages.isLanguageRole(role.getId()));
    }

    /**
     * Writes the member's language.
     *
     * No language role at all is {@link Optional#empty()} and nothing is written: the column defaults to
     * English, and overwriting a real choice because onboarding is mid-flight would be worse than being a
     * little stale.
     */
    private void mirrorLocale(final Member member) {
        languages
                .resolve(member.getRoles().stream().map(Role::getId).toList())
                .ifPresent(language -> access.setLocale(member.getId(), language.locale()));
    }

    /** Drops an admin who left or was banned, with everybody they granted. */
    private void dropAdmin(final String discordId) {
        final java.util.Set<String> dropped = admins.dropWithBranch(discordId);
        if (!dropped.isEmpty()) {
            log.info(
                    "{} is no longer in the guild; {} admin(s) dropped with them: {}",
                    discordId,
                    dropped.size(),
                    dropped);
        }
    }

    /**
     * Writes the username, effective nickname and effective avatar this event or reconcile pass just observed.
     *
     * Piggybacks on the same two visits every other projection in this class already makes - the member-join event and
     * the startup reconcile's member loop - rather than a loop of its own.
     *
     * {@code getEffectiveName()} and {@code getEffectiveAvatarUrl()} fall back to the global profile when a member has
     * set no guild-scoped nickname or avatar, which a per-guild avatar almost never is. This column therefore does not
     * distinguish a guild picture from an account picture; nothing reads it that cares, and {@code clearGuildProfile}
     * still empties it when somebody leaves, because what is cached here is only known through this guild either
     * way.
     */
    private void mirrorProfile(final Member member) {
        access.setDiscordProfile(
                member.getId(), member.getUser().getName(), member.getEffectiveName(), member.getEffectiveAvatarUrl());
    }
}
