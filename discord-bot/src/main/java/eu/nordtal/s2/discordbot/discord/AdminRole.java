package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AdminTree;
import eu.nordtal.s2.database.alert.DiscordRole;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.AlertOnce;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.roles.GuildRoles;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

/**
 * Keeps the Discord admin role matching the admin tree in the database, never the other way round.
 *
 * A failure is alerted once per account and then kept quiet until it succeeds.
 */
@Slf4j
public final class AdminRole {

    private final JDA jda;
    private final AccessSpec config;
    private final GuildRoles roles;
    private final AdminTree admins;
    private final AlertOnce alerts;

    public AdminRole(
            final JDA jda,
            final AccessSpec config,
            final GuildRoles roles,
            final AdminTree admins,
            final AdminLog adminLog) {
        this.jda = jda;
        this.config = config;
        this.roles = roles;
        this.admins = admins;
        this.alerts = new AlertOnce(adminLog::alert);
    }

    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; the admin role was not reconciled", config.guildId());
            return;
        }
        final Role role = roles.role(guild, GuildRoles.ADMIN).orElse(null);
        if (role == null) {
            // GuildRoles has said why.
            log.warn("There is no admin role yet; it was not reconciled");
            return;
        }

        final Set<String> shouldHave = new HashSet<>();
        admins.admins().forEach(admin -> shouldHave.add(admin.discordId().value()));

        for (final Member member : guild.getMembersWithRoles(role)) {
            if (!shouldHave.remove(member.getId())) {
                guild.removeRoleFromMember(member, role)
                        .queue(
                                ok -> succeeded(DiscordId.of(member.getId()), "took the admin role from"),
                                failure -> failed(DiscordId.of(member.getId()), false, failure));
            }
        }

        for (final DiscordId discordId : shouldHave.stream().map(DiscordId::of).toList()) {
            final Member member = guild.getMemberById(discordId.value());
            if (member == null) {
                // Not in the member cache: leaving drops the admin anyway.
                continue;
            }
            guild.addRoleToMember(member, role)
                    .queue(
                            ok -> succeeded(discordId, "gave the admin role to"),
                            failure -> failed(discordId, true, failure));
        }
    }

    private void succeeded(final DiscordId discordId, final String what) {
        alerts.clear(discordId.value());
        log.info("Admin role: {} {}", what, discordId);
    }

    private void failed(final DiscordId discordId, final boolean given, final Throwable failure) {
        if (!alerts.raise(discordId.value(), GuildRoles.notChanged(DiscordRole.ADMIN, given, discordId, failure))) {
            log.debug("Could still not change the admin role of {}: {}", discordId, failure.getMessage());
        }
    }
}
