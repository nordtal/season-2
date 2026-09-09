package eu.nordtal.s2.common.phase;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.time.Instant;
import java.util.Optional;

/**
 * The whole SQL surface of the phase model, as a JDBI SqlObject interface. Package-private on
 * purpose: {@link PhaseDirectory} is the API, and no consumer should hold a {@code Jdbi} or a DAO.
 */
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
     * The switch, the audit entry and the notification as <b>one statement</b>, so that there is no
     * way to issue the {@code UPDATE} through this DAO without the audit {@code INSERT} riding
     * along - both writers of the phase must record who did it.
     *
     * <p>Every sub-statement of a {@code WITH} sees the same snapshot, so {@code previous} reads the
     * row as it was before {@code switched} replaced it. {@code audited} is referenced by nothing,
     * which does not matter: a data-modifying CTE runs exactly once regardless.
     *
     * <p>{@code pg_notify} rides in the select list, so a notification is only ever emitted for a
     * switch that committed. It carries <b>no payload</b> on purpose: notifications are lost while a
     * process is disconnected, so a listener must re-read the row and must never trust the
     * notification as state.
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

    /**
     * Writes {@code launch}, with its audit entry and notification, as one statement. Nothing is
     * derived from that column, which is the whole difference to
     * {@link #setSmpStart(Instant, String)} and why these are two statements.
     *
     * @param at    the new instant, or {@code null} to clear the date
     * @param actor the Discord id of the admin who asked for it
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
    DateChange setLaunch(@Bind("at") Instant at, @Bind("actor") String actor);

    /**
     * Writes {@code smp_start}, moves the paid access that was anchored to it, and files the audit
     * entry and notification - all as one statement.
     *
     * <p>A grant moves when it is not revoked, has not run out, and began at or after the date being
     * replaced; setting the date for the first time therefore moves every live grant, which is the
     * case this exists for.
     *
     * <p>The shift is computed <b>per Discord account</b>: each account's earliest moving grant is
     * placed on the new date and the rest keep their distance from it, so stacked periods stay
     * stacked and two people who bought on different days both start when the SMP opens. An account
     * already sitting on the new date is left alone.
     *
     * @param at    the new instant, or {@code null} to clear the date - <b>clearing moves no
     *              grants</b>, since there is no date left for them to be anchored to
     * @param actor the Discord id of the admin who asked for it
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
    DateChange setSmpStart(@Bind("at") Instant at, @Bind("actor") String actor);
}
