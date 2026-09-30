package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AdminTree;
import eu.nordtal.s2.discordbot.AdminLog;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final AdminTree admins;
    private final AdminLog adminLog;
    private final Set<String> alerted = ConcurrentHashMap.newKeySet();

    public AdminRole(final JDA jda, final AccessSpec config, final AdminTree admins, final AdminLog adminLog) {
        this.jda = jda;
        this.config = config;
        this.admins = admins;
        this.adminLog = adminLog;
    }

    public void reconcile() {
        final Guild guild = jda.getGuildById(config.guildId());
        if (guild == null) {
            log.error("Guild {} is not available; the admin role was not reconciled", config.guildId());
            return;
        }
        final Role role = guild.getRoleById(config.roles().admin());
        if (role == null) {
            if (alerted.add("role")) {
                adminLog.alert(
                        "⚠️ Admin role missing",
                        "`" + config.roles().admin() + "` does not exist, so it is not kept in step with Steward.");
            }
            return;
        }
        alerted.remove("role");

        final Set<String> shouldHave = new HashSet<>();
        admins.admins().forEach(admin -> shouldHave.add(admin.discordId().value()));

        for (final Member member : guild.getMembersWithRoles(role)) {
            if (!shouldHave.remove(member.getId())) {
                guild.removeRoleFromMember(member, role)
                        .queue(
                                ok -> succeeded(DiscordId.of(member.getId()), "took the admin role from"),
                                failure -> failed(DiscordId.of(member.getId()), "take the admin role from", failure));
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
                            failure -> failed(discordId, "give the admin role to", failure));
        }
    }

    private void succeeded(final DiscordId discordId, final String what) {
        alerted.remove(discordId.value());
        log.info("Admin role: {} {}", what, discordId);
    }

    private void failed(final DiscordId discordId, final String what, final Throwable failure) {
        if (alerted.add(discordId.value())) {
            adminLog.alert("⚠️ Admin role not changed", "<@" + discordId + "> " + what + ": " + failure.getMessage());
        } else {
            log.debug("Could still not {} {}: {}", what, discordId, failure.getMessage());
        }
    }
}
