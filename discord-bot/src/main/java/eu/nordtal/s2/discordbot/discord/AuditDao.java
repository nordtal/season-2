package eu.nordtal.s2.discordbot.discord;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.UUID;

/**
 * This module's writer of {@code audit_log}. The table is append-only by discipline - nothing in
 * this codebase issues an {@code UPDATE} or {@code DELETE} against it - and this interface is that
 * discipline made visible.
 * <p>
 * <b>It is not the only writer any more.</b> {@code :common}'s phase DAO
 * ({@code eu.nordtal.s2.common.phase.PhaseDao}) writes the row for a phase switch itself, in the
 * same statement that updates {@code season_phase} - deliberately, so that the phase cannot be
 * changed without the audit entry, by this bot or by the proxy's emergency command
 * ({@code docs/season-phases.md#who-may-switch-it}). A {@code /phase set} therefore records itself
 * and must not be passed through {@link AdminLog#record} as well, or the switch appears twice.
 * </p>
 */
interface AuditDao {

    @SqlUpdate("""
            INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
            VALUES (:action, :actor, :subject, :mcUuid, :detail)
            """)
    void record(@Bind("action") String action,
                @Bind("actor") String actor,
                @Bind("subject") String subject,
                @Bind("mcUuid") UUID mcUuid,
                @Bind("detail") String detail);
}
