package eu.nordtal.s2.common.phase;

import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jspecify.annotations.Nullable;

/** The SQL surface of the phase model; {@link PhaseDirectory} is the API. */
interface PhaseDao {

    /**
     * @return the stored phase name, empty only if the singleton row has been deleted - {@code V4}
     *         seeds it and nothing removes it
     */
    @SqlQuery("SELECT phase FROM season_phase WHERE id")
    Optional<String> currentPhase();

    /**
     * @return the announced opening instant, empty when the column is {@code NULL} or the singleton
     *         row is gone - the caller cannot tell those apart and has no reason to
     */
    @SqlQuery("SELECT launch FROM season_phase WHERE id")
    Optional<Instant> launch();

    /**
     * @return the announced instant paid access starts running, empty when the column is
     *         {@code NULL} or the singleton row is gone
     */
    @SqlQuery("SELECT smp_start FROM season_phase WHERE id")
    Optional<Instant> smpStart();

    /**
     * Switches the phase, writes the audit entry and notifies, as one statement.
     *
     * The notification carries no payload and is emitted only on commit; listeners re-read the row.
     *
     * @param actor  the Discord id of the admin who caused it, or {@code null}
     * @param reason free text appended to the audit detail in brackets, or {@code null}
     * @return the previous phase, the new phase and when; no row if the singleton is missing
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
    @Nullable
    PhaseChange switchPhase(
            @Bind("phase") String phase,
            @Bind("actor") @Nullable String actor,
            @Bind("reason") @Nullable String reason);

    /**
     * Writes {@code launch} with its audit entry and notification, as one statement.
     *
     * @param at the new instant, or {@code null} to clear the date
     * @return the row's value before and after; the two grant counts are always zero
     */
    @SqlQuery("""
            WITH previous AS (
                SELECT launch FROM season_phase WHERE id
            ),
                 written AS (
                     UPDATE season_phase
                     SET launch = cast(:at AS timestamptz), updated = now()
                     WHERE id
                     RETURNING launch
                 ),
                 audited AS (
                     INSERT INTO audit_log (action, actor, detail)
                     SELECT 'SET_LAUNCH',
                            cast(:actor AS varchar(32)),
                            coalesce(cast(previous.launch AS text), 'not set') || ' -> '
                                || coalesce(cast(written.launch AS text), 'not set')
                     FROM previous, written
                 )
            SELECT previous.launch                AS previous_at,
                   written.launch                 AS current_at,
                   0                              AS moved_grants,
                   0                              AS moved_accounts,
                   pg_notify('nordtal_phase', '') AS notified
            FROM previous, written
            """)
    @RegisterRowMapper(DateChangeMapper.class)
    @Nullable
    DateChange setLaunch(@Bind("at") @Nullable Instant at, @Bind("actor") @Nullable String actor);

    /**
     * Writes {@code smp_start} and moves the paid access anchored to it, with audit and notification.
     *
     * A live grant that began at or after the old date moves. The shift is per Discord account: its earliest
     * moving grant lands on the new date and the rest keep their distance. Clearing the date moves nothing.
     *
     * @param at the new instant, or {@code null} to clear the date
     * @return the row's value before and after, and how much access moved with it
     */
    @SqlQuery("""
            WITH previous AS (
                SELECT smp_start FROM season_phase WHERE id
            ),
                 movable AS (
                     SELECT grant_row.id, grant_row.discord_id, grant_row.valid_from
                     FROM access_grant grant_row, previous
                     WHERE cast(:at AS timestamptz) IS NOT NULL
                       AND grant_row.revoked IS NULL
                       AND grant_row.valid_until > now()
                       AND (previous.smp_start IS NULL
                            OR grant_row.valid_from >= previous.smp_start)
                 ),
                 anchors AS (
                     -- Seconds, never days: subtracting two timestamptz values yields a day-based
                     -- interval, and adding one back is calendar arithmetic in the session's time
                     -- zone, so a period moved across a clock change would change length.
                     SELECT discord_id,
                            make_interval(secs => cast(extract(epoch FROM
                                (cast(:at AS timestamptz) - min(valid_from))) AS double precision))
                                AS shift
                     FROM movable
                     GROUP BY discord_id
                     HAVING min(valid_from) <> cast(:at AS timestamptz)
                 ),
                 moved AS (
                     UPDATE access_grant grant_row
                     SET valid_from  = grant_row.valid_from  + anchor.shift,
                         valid_until = grant_row.valid_until + anchor.shift
                     FROM movable candidate
                              JOIN anchors anchor ON anchor.discord_id = candidate.discord_id
                     WHERE grant_row.id = candidate.id
                     RETURNING grant_row.id, grant_row.discord_id
                 ),
                 written AS (
                     UPDATE season_phase
                     SET smp_start = cast(:at AS timestamptz), updated = now()
                     WHERE id
                     RETURNING smp_start
                 ),
                 audited AS (
                     INSERT INTO audit_log (action, actor, detail)
                     SELECT 'SET_SMP_START',
                            cast(:actor AS varchar(32)),
                            coalesce(cast(previous.smp_start AS text), 'not set') || ' -> '
                                || coalesce(cast(written.smp_start AS text), 'not set')
                                || ' (' || (SELECT count(*) FROM moved) || ' grants moved)'
                     FROM previous, written
                 )
            SELECT previous.smp_start                        AS previous_at,
                   written.smp_start                         AS current_at,
                   (SELECT count(*) FROM moved)              AS moved_grants,
                   (SELECT count(DISTINCT discord_id)
                    FROM moved)                              AS moved_accounts,
                   pg_notify('nordtal_phase', '')            AS notified
            FROM previous, written
            """)
    @RegisterRowMapper(DateChangeMapper.class)
    @Nullable
    DateChange setSmpStart(@Bind("at") @Nullable Instant at, @Bind("actor") @Nullable String actor);
}
