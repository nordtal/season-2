package eu.nordtal.s2.common.phase;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.Optional;

/**
 * The whole SQL surface of the phase model, as a JDBI SqlObject interface - the same style as
 * {@code AccessDao}.
 * <p>
 * Package-private on purpose: {@link PhaseDirectory} is the API, this is how it is implemented, and
 * no consumer should ever hold a {@code Jdbi} or a DAO of ours.
 * </p>
 */
interface PhaseDao {

    /**
     * @return the stored phase name, empty only if the singleton row has been deleted - {@code V4}
     *         seeds it and nothing removes it
     */
    @SqlQuery("SELECT phase FROM season_phase WHERE id")
    Optional<String> currentPhase();

    /**
     * The switch, the audit entry and the notification as <b>one statement</b>.
     *
     * <h2>Why one statement and not three calls in a transaction</h2>
     * {@code docs/season-phases.md#who-may-switch-it} requires that both writers - the bot's
     * {@code /phase set} and the proxy's emergency command - write the audit entry, and says
     * plainly that two writers means two places where that is easy to forget. A transaction in a
     * shared helper would already fix that; a single statement is the stronger form of it, because
     * there is no way to issue the {@code UPDATE} through this DAO without the {@code INSERT}
     * riding along. It is also one round trip on a path that may be taken while the network is
     * already in trouble.
     *
     * <h2>How the "before" value survives the update</h2>
     * Every sub-statement of a {@code WITH} sees the same snapshot, so {@code previous} reads the
     * row as it was <em>before</em> {@code switched} replaced it. {@code audited} is never
     * referenced by the outer query, which does not matter: PostgreSQL executes a data-modifying
     * CTE exactly once and to completion whether or not anything reads its output.
     *
     * <h2>The notification</h2>
     * {@code pg_notify} rides in the select list so that it, too, is part of the same statement and
     * the same transaction - a notification is only ever emitted for a switch that actually
     * committed. It carries <b>no payload</b> on purpose: a listener must re-read the row anyway,
     * because notifications are lost while a process is disconnected, and a payload would invite
     * somebody to trust the notification as state. The channel name is {@code nordtal_phase},
     * settled 2026-08-31 alongside the 30-second poll that is the actual guarantee.
     *
     * @param phase  the phase name to store; the column's CHECK constraint rejects anything that is
     *               not a {@code SeasonPhase} constant
     * @param actor  the Discord id of the admin who caused it, or {@code null}
     * @param reason free text appended to the audit detail in brackets, or {@code null}
     * @return one row: the previous phase, the new phase and when it was recorded; no rows at all
     *         if the singleton row is missing
     */
    @SqlQuery("""
            WITH previous AS (
                SELECT phase FROM season_phase WHERE id
            ),
                 switched AS (
                     UPDATE season_phase
                     SET phase = :phase, updated = now()
                     WHERE id
                     RETURNING phase, updated
                 ),
                 audited AS (
                     INSERT INTO audit_log (action, actor, detail)
                     SELECT 'SET_PHASE',
                            cast(:actor AS varchar(32)),
                            previous.phase || ' -> ' || switched.phase
                                || coalesce(' (' || cast(:reason AS text) || ')', '')
                     FROM previous, switched
                 )
            SELECT previous.phase                 AS previous_phase,
                   switched.phase                 AS current_phase,
                   switched.updated               AS changed,
                   pg_notify('nordtal_phase', '') AS notified
            FROM previous, switched
            """)
    @RegisterRowMapper(PhaseChangeMapper.class)
    PhaseChange switchPhase(@Bind("phase") String phase,
                            @Bind("actor") String actor,
                            @Bind("reason") String reason);
}
