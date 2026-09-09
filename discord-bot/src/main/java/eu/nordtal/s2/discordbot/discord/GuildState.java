package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.discordbot.access.discord.ReconcileDao;

import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.MemberState;

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
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps {@code discord_user.member_state}, {@code locale} and {@code admin} current. The proxy
 * decides whether a login is allowed and cannot ask Discord anything, so these are projections the
 * bot maintains: from gateway events while it runs, and from one reconcile at startup.
 *
 * <p>Language and admin are both mirrored from Discord roles the bot never assigns. They differ in
 * one way: losing the admin role clears the flag, while no language role leaves the stored value
 * alone. "No language" has a safe answer (English) and "no longer an admin" does not - a stale
 * {@code true} would let somebody through {@code MAINTENANCE} and switch the season phase.</p>
 *
 * <p>Leaving the guild removes the account link. Nothing is lost with it: play time, aura and
 * grants hang off {@code discord_user}, so re-linking the same account restores them. A ban is a
 * removal too - Discord sends the remove event either way.</p>
 *
 * <p>The startup reconcile deletes links only when it can see the whole guild: writing
 * {@code LEFT} is repaired by the next pass, deleting a link is not, and an incompletely chunked
 * member cache would take every link in the guild with it.</p>
 *
 * <p>A ban does not pause anything - {@code BANNED} refuses the login while the paid period keeps
 * running down. This class writes state and never touches a grant.</p>
 */
@Slf4j
public final class GuildState extends ListenerAdapter {

    private final JDA jda;
    private final AccessSpec config;
    private final Languages languages;
    private final AccessDirectory access;
    private final ReconcileDao dao;

    public GuildState(final JDA jda, final AccessSpec config, final Languages languages,
                      final AccessDirectory access, final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
        this.languages = languages;
        this.access = access;
        this.dao = jdbi.onDemand(ReconcileDao.class);
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onGuildMemberJoin(final @NotNull GuildMemberJoinEvent event) {
        if (!ours(event.getGuild()) || event.getMember().getUser().isBot()) {
            return;
        }
        access.setMemberState(event.getMember().getId(), MemberState.MEMBER);
        mirrorLocale(event.getMember());
        mirrorAdmin(event.getMember());
    }

    @Override
    public void onGuildMemberRemove(final @NotNull GuildMemberRemoveEvent event) {
        if (!ours(event.getGuild()) || event.getUser().isBot()) {
            return;
        }
        // A ban also produces a remove. GuildBanEvent arrives too and overwrites this with BANNED;
        // the order is not guaranteed, which is why the startup reconcile re-derives both from the
        // ban list rather than trusting the sequence.
        access.setMemberState(event.getUser().getId(), MemberState.LEFT);
        // Somebody who is not in the guild cannot be holding a role in it. The flag would otherwise
        // survive a removal and let an ex-member switch the season phase from the proxy.
        access.setAdmin(event.getUser().getId(), false);
        // And they are not a linked member either. Safe here in a way it is not in reconcile():
        // this is one named user Discord has told us about, not an inference from a list that may
        // have loaded incompletely.
        if (access.unlink(event.getUser().getId())) {
            log.info("{} left the guild; their Minecraft account link was removed", event.getUser().getId());
        }
    }

    @Override
    public void onGuildBan(final @NotNull GuildBanEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        access.setMemberState(event.getUser().getId(), MemberState.BANNED);
    }

    @Override
    public void onGuildUnban(final @NotNull GuildUnbanEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        // Unbanning does not put anybody back in the guild - they have to rejoin, which produces a
        // join event. LEFT, not MEMBER.
        access.setMemberState(event.getUser().getId(), MemberState.LEFT);
    }

    @Override
    public void onGuildMemberRoleAdd(final @NotNull GuildMemberRoleAddEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        if (touchesLanguage(event.getRoles())) {
            mirrorLocale(event.getMember());
        }
        if (touchesAdmin(event.getRoles())) {
            mirrorAdmin(event.getMember());
        }
    }

    @Override
    public void onGuildMemberRoleRemove(final @NotNull GuildMemberRoleRemoveEvent event) {
        if (!ours(event.getGuild())) {
            return;
        }
        if (touchesLanguage(event.getRoles())) {
            mirrorLocale(event.getMember());
        }
        if (touchesAdmin(event.getRoles())) {
            mirrorAdmin(event.getMember());
        }
    }

    // ---------------------------------------------------------------- startup

    /**
     * Catches up on everything that happened while the bot was down, in three passes: everybody in
     * the guild is a {@code MEMBER}, everybody on the ban list is {@code BANNED}, and everybody we
     * know about who is in neither has {@code LEFT}. The last is the one no event could deliver.
     *
     * <p>The last two passes also clear the admin flag, which is why it is mirrored here and not
     * only on role events: a role taken away while the bot was down produces no event.</p>
     *
     * <p>The third pass deletes the account link only when the picture is complete - see
     * {@link #memberCacheLooksComplete(int, int)}. That is the one place where being wrong is not
     * repaired by the next run.</p>
     */
    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; guild state was not reconciled", config.guildId());
            return;
        }

        final Set<String> seen = new HashSet<>();

        // One snapshot for both the pass and the completeness decision. Reading the cache twice
        // would let somebody join between the reads: missing from `seen`, counted in the size, so
        // the check passes and the third pass deletes the link of a member who had just arrived.
        final List<Member> members = guild.getMemberCache().asList();

        for (final Member member : members) {
            if (member.getUser().isBot()) {
                continue;
            }
            access.setMemberState(member.getId(), MemberState.MEMBER);
            mirrorLocale(member);
            mirrorAdmin(member);
            seen.add(member.getId());
        }

        boolean banListRead = true;
        try {
            guild.retrieveBanList().stream().forEach(ban -> {
                access.setMemberState(ban.getUser().getId(), MemberState.BANNED);
                access.setAdmin(ban.getUser().getId(), false);
                seen.add(ban.getUser().getId());
            });
        } catch (final RuntimeException exception) {
            banListRead = false;
            log.error("Could not read the ban list; banned users may still be marked as members", exception);
        }

        // Read AFTER the snapshot: somebody joining during the pass raises the count while the
        // snapshot does not, so the check fails and nothing is deleted. The safe direction.
        final int expected = guild.getMemberCount();
        final boolean mayUnlink = memberCacheLooksComplete(members.size(), expected) && banListRead;

        int left = 0;
        int unlinked = 0;
        for (final String discordId : dao.allUsers()) {
            if (!seen.contains(discordId)) {
                access.setMemberState(discordId, MemberState.LEFT);
                access.setAdmin(discordId, false);
                left++;
                if (mayUnlink && access.unlink(discordId)) {
                    unlinked++;
                }
            }
        }

        log.info("Reconciled guild state: {} member(s), {} known account(s) no longer present,"
                + " {} link(s) removed", seen.size(), left, unlinked);
        if (!mayUnlink && left > 0) {
            log.warn("Account links were left in place for those {} account(s): the member cache"
                    + " holds {} of {} member(s) and the ban list {} read. Deleting on an"
                    + " incomplete picture would unlink the whole guild; the next reconcile that"
                    + " sees everything will do it.",
                    left, members.size(), expected, banListRead ? "was" : "was not");
        }
    }

    /**
     * Whether the member cache can be trusted to answer "who is in this guild" - the only thing
     * standing between an unlucky startup and every account link in the database, which is why it
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

    // ---------------------------------------------------------------- helpers

    private boolean ours(final Guild guild) {
        return config.guildId().equals(guild.getId());
    }

    private boolean touchesLanguage(final List<Role> changed) {
        return changed.stream().anyMatch(role -> languages.isLanguageRole(role.getId()));
    }

    private boolean touchesAdmin(final List<Role> changed) {
        return changed.stream().anyMatch(role -> role.getId().equals(config.roles().admin()));
    }

    /**
     * Writes the member's language. No language role at all is {@link Optional#empty()} and nothing
     * is written: the column defaults to English, and overwriting a real choice because onboarding
     * is mid-flight would be worse than being a little stale.
     */
    private void mirrorLocale(final Member member) {
        languages.resolve(member.getRoles().stream().map(Role::getId).toList())
                .ifPresent(language -> access.setLocale(member.getId(), language.locale()));
    }

    /**
     * Writes whether the member holds the admin role right now, {@code false} included - unlike
     * {@link #mirrorLocale(Member)}, which never writes an absence. The flag authorises
     * {@code /phase set} and admission during {@code MAINTENANCE}, so a value that is only ever
     * raised would keep every admin who has ever been one.
     */
    private void mirrorAdmin(final Member member) {
        access.setAdmin(member.getId(), member.getRoles().stream()
                .anyMatch(role -> role.getId().equals(config.roles().admin())));
    }
}
