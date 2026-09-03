package eu.nordtal.s2.common.phase;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.time.Instant;
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
     * @return the announced opening instant, empty when the column is {@code NULL} or the singleton
     *         row is gone - the caller cannot tell those apart and has no reason to
     */
    @SqlQuery("SELECT launch FROM season_phase WHERE id")
    Optional<Instant> launch();

    /**
     * @return the announced instant paid access starts running, empty when the column is
     *         {@code NULL} or the singleton row is gone - {@code V9__smp_start.sql}
     */
    @SqlQuery("SELECT smp_start FROM season_phase WHERE id")
    Optional<Instant> smpStart();

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

    /**
     * Writes {@code launch}, with its audit entry and notification, as one statement.
     * <p>
     * It owns nothing but its own column: {@code launch} is what the server browser counts down
     * to, and no other row is derived from it. That is the whole difference to
     * {@link #setSmpStart(Instant, String)}, and the reason these are two statements rather than
     * one with a column name in it.
     * </p>
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
     * <h2>Which grants move, and by how much</h2>
     * A grant moves when it is not revoked, has not already run out, and began at or after the
     * date being replaced. When the date is being set for the first time there is nothing to
     * compare against, so every live grant qualifies - that is the case this exists for: access
     * sold while the season had no date starts at {@code now()}, and setting the date is what
     * repairs it.
     * <p>
     * The shift is computed <b>per Discord account</b>, not once for the whole table: each
     * account's earliest moving grant is placed on the new date and the rest of that account's
     * grants keep their distance from it. Stacked periods therefore stay stacked - two thirty-day
     * purchases remain sixty consecutive days - and two people who bought on different days both
     * start when the SMP opens rather than one of them starting late. Shifting the table by a
     * single delta would get the second half of that wrong.
     * </p>
     * <p>
     * An account whose earliest grant already sits on the new date is left alone, so writing the
     * same date twice moves nothing and reports nothing.
     * </p>
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
                     -- The shift is seconds, never days. Subtracting two timestamptz values yields
                     -- a day-based interval, and adding one of those back is calendar arithmetic in
                     -- the session's time zone - so a thirty-day period moved across the October
                     -- clock change would come out thirty days and one hour long. AccessDao writes
                     -- these windows with make_interval(hours => ...) for the same reason; a shift
                     -- that did not match it would silently change what somebody paid for.
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
