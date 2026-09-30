package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AdminTree;
import eu.nordtal.s2.database.access.MemberState;
import eu.nordtal.s2.discordbot.access.discord.ReconcileDao;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import java.util.HashSet;
import java.util.List;
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
 * Mirrors guild membership, language, name and face into {@code discord_user}, from events and one startup reconcile.
 *
 * Leaving the guild drops the account link and the admin branch; a ban writes state and never touches a grant.
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

    @Override
    public void onGuildMemberJoin(final GuildMemberJoinEvent event) {
        if (!ours(event.getGuild()) || event.getMember().getUser().isBot()) {
            return;
        }
        access.setMemberState(DiscordId.of(event.getMember().getId()), MemberState.MEMBER);
        mirrorLocale(event.getMember());
        mirrorProfile(event.getMember());
    }

    @Override
    public void onGuildMemberRemove(final GuildMemberRemoveEvent event) {
        if (!ours(event.getGuild()) || event.getUser().isBot()) {
            return;
        }
        // A ban also produces a remove; GuildBanEvent then overwrites this with BANNED.
        access.setMemberState(DiscordId.of(event.getUser().getId()), MemberState.LEFT);
        // Somebody not in the guild is not an admin, nor is anybody they granted.
        dropAdmin(DiscordId.of(event.getUser().getId()));
        // Nor a guild nickname or avatar, both scoped to the guild they left.
        access.clearGuildProfile(DiscordId.of(event.getUser().getId()));
        // Safe here, unlike in reconcile(): Discord named this one user directly.
        if (access.unlink(DiscordId.of(event.getUser().getId()))) {
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
        access.setMemberState(DiscordId.of(event.getUser().getId()), MemberState.BANNED);
        dropAdmin(DiscordId.of(event.getUser().getId()));
        access.clearGuildProfile(DiscordId.of(event.getUser().getId()));
    }

    @Override
    public void onGuildUnban(final GuildUnbanEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        // Unbanning does not put anybody back in the guild, so LEFT, not MEMBER.
        access.setMemberState(DiscordId.of(event.getUser().getId()), MemberState.LEFT);
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

    /**
     * Catches up on everything that happened while the bot was down, in three passes.
     *
     * Links and admins are dropped only when {@link #memberCacheLooksComplete(int, int)} trusts the member cache.
     */
    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; guild state was not reconciled", config.guildId());
            return;
        }

        // One snapshot for the pass and the completeness decision, so a mid-read join cannot fool the count.
        final MemberSweep sweep = sweepMembers(guild);
        final boolean banListRead = sweepBanList(guild, sweep.seen());

        // Read after the snapshot: a mid-pass join raises the count, so the check fails safely.
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

    private record MemberSweep(Set<String> seen, int cached) {}

    private record Removal(int left, int unlinked) {}

    private MemberSweep sweepMembers(final Guild guild) {
        final Set<String> seen = new HashSet<>();
        final List<Member> members = guild.getMemberCache().asList();
        for (final Member member : members) {
            if (member.getUser().isBot()) {
                continue;
            }
            access.setMemberState(DiscordId.of(member.getId()), MemberState.MEMBER);
            mirrorLocale(member);
            mirrorProfile(member);
            seen.add(member.getId());
        }
        return new MemberSweep(seen, members.size());
    }

    private boolean sweepBanList(final Guild guild, final Set<String> seen) {
        try {
            guild.retrieveBanList().stream().forEach(ban -> {
                access.setMemberState(DiscordId.of(ban.getUser().getId()), MemberState.BANNED);
                dropAdmin(DiscordId.of(ban.getUser().getId()));
                access.clearGuildProfile(DiscordId.of(ban.getUser().getId()));
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
        for (final DiscordId discordId :
                dao.allUsers().stream().map(DiscordId::of).toList()) {
            if (!seen.contains(discordId.value())) {
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
     * Returns whether the member cache can be trusted to say who is in the guild.
     *
     * @param cached how many members the snapshot holds
     * @param expected how many the guild says it has; {@code 0} or less means "cannot tell"
     * @return whether links may be deleted on the strength of this cache
     */
    static boolean memberCacheLooksComplete(final int cached, final int expected) {
        return expected > 0 && cached >= expected;
    }

    private boolean ours(final Guild guild) {
        return config.guildId().equals(guild.getId());
    }

    private boolean touchesLanguage(final List<Role> changed) {
        return changed.stream().anyMatch(role -> languages.isLanguageRole(role.getId()));
    }

    /** Writes the member's language, and nothing when they hold no language role. */
    private void mirrorLocale(final Member member) {
        languages
                .resolve(member.getRoles().stream().map(Role::getId).toList())
                .ifPresent(language -> access.setLocale(DiscordId.of(member.getId()), language.locale()));
    }

    /** Drops an admin who left or was banned, with everybody they granted. */
    private void dropAdmin(final DiscordId discordId) {
        final java.util.Set<String> dropped = admins.dropWithBranch(discordId);
        if (!dropped.isEmpty()) {
            log.info(
                    "{} is no longer in the guild; {} admin(s) dropped with them: {}",
                    discordId,
                    dropped.size(),
                    dropped);
        }
    }

    /** Writes the username, effective nickname and effective avatar just observed. */
    private void mirrorProfile(final Member member) {
        access.setDiscordProfile(
                DiscordId.of(member.getId()),
                member.getUser().getName(),
                member.getEffectiveName(),
                member.getEffectiveAvatarUrl());
    }
}
