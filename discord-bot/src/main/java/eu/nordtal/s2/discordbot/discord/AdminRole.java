package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.access.AdminTree;
import eu.nordtal.s2.discordbot.config.AccessSpec;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Discord admin role, kept to match the admin tree in the database - one way.
 *
 * <p>Admins are granted and revoked in Steward. The role is a decoration of that decision: this
 * adds it to every admin in the guild and takes it from everybody else who holds it, a role given
 * by hand in Discord included. Nothing here ever writes the database.</p>
 *
 * <p>It runs on every wake of the access listener - a grant or revocation notifies
 * {@code nordtal_admin}, and the thirty-second poll catches a notification that was lost and a
 * role somebody handed out by hand.</p>
 *
 * <p>A failure is alerted once per account and then kept quiet until it succeeds: a bot whose role
 * sits below the admin role would otherwise post the same line every thirty seconds.</p>
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
                adminLog.alert("The admin role " + config.roles().admin() + " does not exist, so it"
                        + " is not being kept in step with the admins decided in Steward.");
            }
            return;
        }
        alerted.remove("role");

        final Set<String> shouldHave = new HashSet<>();
        admins.admins().forEach(admin -> shouldHave.add(admin.discordId()));

        for (final Member member : guild.getMembersWithRoles(role)) {
            if (!shouldHave.remove(member.getId())) {
                guild.removeRoleFromMember(member, role).queue(
                        ok -> succeeded(member.getId(), "took the admin role from"),
                        failure -> failed(member.getId(), "take the admin role from", failure));
            }
        }

        // Whatever is left is an admin without the role.
        for (final String discordId : shouldHave) {
            final Member member = guild.getMemberById(discordId);
            if (member == null) {
                // Not in the member cache. Leaving drops the admin anyway, so this is a cache that
                // has not caught up, and the next pass sees them.
                continue;
            }
            guild.addRoleToMember(member, role).queue(
                    ok -> succeeded(discordId, "gave the admin role to"),
                    failure -> failed(discordId, "give the admin role to", failure));
        }
    }

    private void succeeded(final String discordId, final String what) {
        alerted.remove(discordId);
        log.info("Admin role: {} {}", what, discordId);
    }

    private void failed(final String discordId, final String what, final Throwable failure) {
        if (alerted.add(discordId)) {
            adminLog.alert("Could not " + what + " <@" + discordId + ">: " + failure.getMessage());
        } else {
            log.debug("Could still not {} {}: {}", what, discordId, failure.getMessage());
        }
    }
}
