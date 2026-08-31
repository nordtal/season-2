package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.discordbot.config.AccessSpec;
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
import java.util.Locale;
import java.util.Set;

/**
 * Keeps {@code discord_user.member_state} and {@code discord_user.locale} current.
 *
 * <h2>Why this exists at all</h2>
 * The proxy decides whether a login is allowed and <b>cannot ask Discord anything</b> - it has a
 * database connection and nothing else. Membership and language are therefore projections that the
 * bot maintains: from gateway events while it is running, and from one reconcile at startup for
 * everything that happened while it was not.
 *
 * <h2>Language is Discord's, not ours</h2>
 * The choice is made through Discord's own onboarding, which assigns a role. The bot never assigns
 * or removes those roles - it mirrors them. That is why a role update is enough to keep the value
 * current for somebody who is offline or changes their mind months later.
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
    private final AccessDirectory access;
    private final ReconcileDao dao;

    public GuildState(final JDA jda, final AccessSpec config, final AccessDirectory access, final Jdbi jdbi) {
        this.jda = jda;
        this.config = config;
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
        if (ours(event.getGuild()) && touchesLanguage(event.getRoles())) {
            mirrorLocale(event.getMember());
        }
    }

    @Override
    public void onGuildMemberRoleRemove(final @NotNull GuildMemberRoleRemoveEvent event) {
        if (ours(event.getGuild()) && touchesLanguage(event.getRoles())) {
            mirrorLocale(event.getMember());
        }
    }

    // ---------------------------------------------------------------- startup

    /**
     * Catches up on everything that happened while the bot was down.
     * <p>
     * Three passes, in this order: everybody currently in the guild is a {@code MEMBER} with their
     * current language; everybody on the ban list is {@code BANNED}; everybody we know about who
     * is in neither has {@code LEFT}. The last pass is the one no event could ever have delivered.
     * </p>
     * <p>
     * The member list comes from JDA's cache, which is chunked once when the session opens. That
     * is the only full member load in the process - the periodic role reconcile reads the same
     * cache.
     * </p>
     */
    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; guild state was not reconciled", config.guildId());
            return;
        }

        final Set<String> seen = new HashSet<>();

        for (final Member member : guild.getMemberCache()) {
            if (member.getUser().isBot()) {
                continue;
            }
            access.setMemberState(member.getId(), MemberState.MEMBER);
            mirrorLocale(member);
            seen.add(member.getId());
        }

        try {
            guild.retrieveBanList().stream().forEach(ban -> {
                access.setMemberState(ban.getUser().getId(), MemberState.BANNED);
                seen.add(ban.getUser().getId());
            });
        } catch (final RuntimeException exception) {
            log.error("Could not read the ban list; banned users may still be marked as members", exception);
        }

        int left = 0;
        for (final String discordId : dao.allUsers()) {
            if (!seen.contains(discordId)) {
                access.setMemberState(discordId, MemberState.LEFT);
                left++;
            }
        }

        log.info("Reconciled guild state: {} member(s), {} known account(s) no longer present",
                seen.size(), left);
    }

    // ---------------------------------------------------------------- helpers

    private boolean ours(final Guild guild) {
        return config.guildId().equals(guild.getId());
    }

    private boolean touchesLanguage(final List<Role> changed) {
        return changed.stream().anyMatch(role ->
                role.getId().equals(config.roles().german()) || role.getId().equals(config.roles().english()));
    }

    /**
     * Writes the member's language, taking German over English when somebody holds both - a
     * German-speaking member who also picked English is better served in German than in a language
     * chosen by whichever role happens to sort first.
     */
    private void mirrorLocale(final Member member) {
        final boolean german = member.getRoles().stream()
                .anyMatch(role -> role.getId().equals(config.roles().german()));
        final boolean english = member.getRoles().stream()
                .anyMatch(role -> role.getId().equals(config.roles().english()));

        if (german) {
            access.setLocale(member.getId(), Locale.GERMAN);
        } else if (english) {
            access.setLocale(member.getId(), Locale.ENGLISH);
        }
        // Neither role: leave whatever is stored. The column defaults to English, and overwriting
        // a real choice because onboarding is mid-flight would be worse than being a little stale.
    }
}
