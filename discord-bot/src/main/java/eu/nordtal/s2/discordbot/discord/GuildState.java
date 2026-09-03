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
 * Keeps {@code discord_user.member_state}, {@code discord_user.locale} and
 * {@code discord_user.admin} current.
 *
 * <h2>Why this exists at all</h2>
 * The proxy decides whether a login is allowed and <b>cannot ask Discord anything</b> - it has a
 * database connection and nothing else. Membership, language and admin status are therefore
 * projections that the bot maintains: from gateway events while it is running, and from one
 * reconcile at startup for everything that happened while it was not.
 *
 * <h2>Language is Discord's, not ours</h2>
 * The choice is made through Discord's own onboarding, which assigns a role. The bot never assigns
 * or removes those roles - it mirrors them. That is why a role update is enough to keep the value
 * current for somebody who is offline or changes their mind months later.
 *
 * <h2>The admin flag is the same kind of projection, and it is two-way</h2>
 * {@code roles.admin} is mirrored into {@code discord_user.admin} the way the language roles are
 * mirrored into {@code locale} ({@code docs/season-phases.md#how-an-admin-is-recognised}), and
 * <b>losing the role clears the flag</b> - it is a live projection of the Discord role, not a
 * one-way grant. The language mirror deliberately does the opposite and leaves a stored value alone
 * when no language role is held, because "no language" has a safe answer (English) and "no longer
 * an admin" does not: a stale {@code true} is what would let somebody through
 * {@code MAINTENANCE} and switch the season phase.
 *
 * <h2>Leaving the guild removes the account link</h2>
 * Decided 2026-09-03. {@code member_state = LEFT} already refuses the login on its own, so this
 * changes no access decision - what it changes is what "linked" means in the database: a member,
 * and not somebody who used to be one. Nothing is lost with it. Play time, aura and access grants
 * hang off {@code discord_user}, not off the link, so re-linking the same Discord account restores
 * all of it; the cost is that a returning member types a fresh link code, which they can get from
 * a single refused login in any phase.
 * <p>
 * <b>A ban is a removal too</b> - Discord sends the remove event either way - so a banned member
 * also loses the link. That is the same answer, for the same reason: they are not in the guild.
 * </p>
 * <p>
 * <b>The startup reconcile deletes links only when it can see the whole guild.</b> Its third pass
 * is "everybody we know about who is in neither list has left", which is safe while the
 * consequence is a column that the next pass can put back. Deleting is not: a member cache that
 * chunked incompletely, or a ban list that failed to load, would take every link in the guild with
 * it and each member would have to link again by hand. So the pass writes {@code LEFT} always and
 * unlinks only when the cache is at least as large as the guild says it is and the ban list came
 * back. When it cannot tell, it says so in the log and leaves the links alone - a link that
 * outlives its member is untidy, and the alternative is not.
 * </p>
 *
 * <h2>A ban does not pause anything</h2>
 * {@code BANNED} refuses the login now; the paid period keeps running down. Unbanned before it
 * ends, the rest is still usable. This class writes the state and nothing else - it never touches
 * a grant.
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
     * Catches up on everything that happened while the bot was down.
     * <p>
     * Three passes, in this order: everybody currently in the guild is a {@code MEMBER} with their
     * current language and their current admin flag; everybody on the ban list is {@code BANNED};
     * everybody we know about who is in neither has {@code LEFT}. The last pass is the one no event
     * could ever have delivered.
     * </p>
     * <p>
     * The second and third passes also clear the admin flag, and that is the point of mirroring it
     * here rather than only on role events: a role taken away, or an admin banned, while the bot
     * was down produces no event to catch up on.
     * </p>
     * <p>
     * The member list comes from JDA's cache, which is chunked once when the session opens. That
     * is the only full member load in the process - the periodic role reconcile reads the same
     * cache.
     * </p>
     * <p>
     * The third pass also deletes the account link, but <b>only when the picture is complete</b>:
     * see {@link #memberCacheLooksComplete(int, int)} and this class's own documentation. That is
     * the one place where being wrong is not repaired by the next run.
     * </p>
     */
    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; guild state was not reconciled", config.guildId());
            return;
        }

        final Set<String> seen = new HashSet<>();

        // One snapshot, taken before the pass and used for both the pass and the decision below.
        // Reading the cache twice is what let somebody join between the two reads: they would be
        // missing from `seen` and counted in the size, so the completeness check would pass and the
        // third pass would delete the link of a member who had just arrived.
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

        // getMemberCount() is read AFTER the snapshot, deliberately: somebody who joins during the
        // pass raises it while the snapshot stays where it was, so the check fails and nothing is
        // deleted. Reading it first would have the opposite effect, which is the direction that
        // costs somebody their link.
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
     * Whether the member cache can be trusted to answer "who is in this guild".
     * <p>
     * Package-private and static so the rule can be tested without a guild - it is the only thing
     * standing between an unlucky startup and every account link in the database.
     * {@code expected} is what the guild itself reports; a chunking pass that was cut short leaves
     * the cache smaller than that. Both come from {@link #reconcile()}'s single member snapshot and
     * a count read <em>after</em> it, so a member who joins mid-pass raises {@code expected} and
     * fails this check rather than passing it - the safe direction, since the alternative deletes
     * the link of somebody who has just arrived. Greater-than-or-equal rather than equal for the
     * other order: somebody who <em>leaves</em> mid-pass is still in the snapshot, so they are in
     * {@code seen} and the third pass never reaches them anyway.
     * </p>
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
     * Writes the member's language, from whatever {@code access.yml} lists.
     * <p>
     * Every rule about which of several held roles wins lives in
     * {@link Languages#resolve(java.util.Collection)}, which is where it can be tested without a
     * guild. No language role at all is {@link Optional#empty()} and nothing is written: the column
     * defaults to English, and overwriting a real choice because onboarding is mid-flight would be
     * worse than being a little stale.
     * </p>
     */
    private void mirrorLocale(final Member member) {
        languages.resolve(member.getRoles().stream().map(Role::getId).toList())
                .ifPresent(language -> access.setLocale(member.getId(), language.locale()));
    }

    /**
     * Writes whether the member holds the admin role right now - {@code false} included.
     * <p>
     * Unlike {@link #mirrorLocale(Member)} this always writes. Not holding the role is a real
     * answer, and the only safe one: the flag authorises {@code /phase set}, the proxy's emergency
     * phase command and admission during {@code MAINTENANCE}, so a value that is only ever raised
     * would keep every admin who has ever been one.
     * </p>
     */
    private void mirrorAdmin(final Member member) {
        access.setAdmin(member.getId(), member.getRoles().stream()
                .anyMatch(role -> role.getId().equals(config.roles().admin())));
    }
}
