package eu.nordtal.season.database.season;

import eu.nordtal.season.common.season.SeasonState;
import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jspecify.annotations.Nullable;

/** The SQL of the season state. {@link SeasonDirectory} is its API. */
interface SeasonDao {

    /** Selects the state before and after a write, which {@link SeasonChangeMapper} reads. */
    String CHANGED = """
            SELECT previous.launch                  AS previous_launch,
                   previous.maintenance             AS previous_maintenance,
                   previous.mode                    AS previous_mode,
                   written.launch,
                   written.maintenance,
                   written.mode,
                   (SELECT count(*) FROM moved)     AS moved_grants,
                   (SELECT count(DISTINCT discord_id)
                    FROM moved)                     AS moved_accounts,
                   pg_notify('nordtal_season', '')  AS notified
            FROM previous, written
            """;

    @SqlQuery("SELECT launch, maintenance, mode FROM season_state WHERE id")
    @RegisterRowMapper(SeasonStateMapper.class)
    Optional<SeasonState> state();

    /** Counts the live grants of every account whose access starts at the launch date. */
    @SqlQuery("""
            WITH anchored AS (
                SELECT DISTINCT grant_row.discord_id, state.launch
                FROM access_grant grant_row
                         JOIN season_state state ON state.id AND grant_row.valid_from = state.launch
                WHERE grant_row.revoked IS NULL
                  AND grant_row.valid_until > now()
            )
            SELECT count(*)
            FROM access_grant grant_row
                     JOIN anchored ON anchored.discord_id = grant_row.discord_id
            WHERE grant_row.revoked IS NULL
              AND grant_row.valid_until > now()
              AND grant_row.valid_from >= anchored.launch
            """)
    int anchoredGrants();

    /**
     * Writes {@code launch} and moves the live access anchored to the date before it by the same time.
     * An account is anchored when a live grant of it starts at that date; a date set where none was moves nothing.
     *
     * @param at the new date, null closes the season
     * @return the state before and after, no row if the singleton is missing
     */
    @SqlQuery("""
            WITH previous AS (
                SELECT launch, maintenance, mode FROM season_state WHERE id
            ),
                 shift AS (
                     -- Seconds, never days: subtracting two timestamptz values yields a day-based
                     -- interval, and adding one back is calendar arithmetic in the session's time
                     -- zone, so a period moved across a clock change would change length.
                     SELECT launch AS anchor,
                            make_interval(secs => cast(extract(epoch FROM
                                (cast(:at AS timestamptz) - launch)) AS double precision)) AS by
                     FROM previous
                     WHERE launch IS NOT NULL
                       AND cast(:at AS timestamptz) IS NOT NULL
                       AND launch <> cast(:at AS timestamptz)
                 ),
                 anchored AS (
                     SELECT DISTINCT grant_row.discord_id
                     FROM access_grant grant_row, shift
                     WHERE grant_row.revoked IS NULL
                       AND grant_row.valid_until > now()
                       AND grant_row.valid_from = shift.anchor
                 ),
                 moved AS (
                     UPDATE access_grant grant_row
                     SET valid_from  = grant_row.valid_from  + shift.by,
                         valid_until = grant_row.valid_until + shift.by
                     FROM shift, anchored
                     WHERE grant_row.discord_id = anchored.discord_id
                       AND grant_row.revoked IS NULL
                       AND grant_row.valid_until > now()
                       AND grant_row.valid_from >= shift.anchor
                     RETURNING grant_row.id, grant_row.discord_id
                 ),
                 written AS (
                     UPDATE season_state
                     SET launch = cast(:at AS timestamptz), updated = now()
                     WHERE id
                     RETURNING launch, maintenance, mode
                 )
            """ + CHANGED)
    @RegisterRowMapper(SeasonChangeMapper.class)
    @Nullable
    SeasonChange setLaunch(@Bind("at") @Nullable Instant at);

    /** Writes {@code maintenance} and moves no access. */
    @SqlQuery("""
            WITH previous AS (
                SELECT launch, maintenance, mode FROM season_state WHERE id
            ),
                 moved AS (
                     SELECT NULL::varchar AS discord_id WHERE false
                 ),
                 written AS (
                     UPDATE season_state
                     SET maintenance = :on, updated = now()
                     WHERE id
                     RETURNING launch, maintenance, mode
                 )
            """ + CHANGED)
    @RegisterRowMapper(SeasonChangeMapper.class)
    @Nullable
    SeasonChange setMaintenance(@Bind("on") boolean on);

    /** Writes {@code mode} and moves no access. */
    @SqlQuery("""
            WITH previous AS (
                SELECT launch, maintenance, mode FROM season_state WHERE id
            ),
                 moved AS (
                     SELECT NULL::varchar AS discord_id WHERE false
                 ),
                 written AS (
                     UPDATE season_state
                     SET mode = :mode, updated = now()
                     WHERE id
                     RETURNING launch, maintenance, mode
                 )
            """ + CHANGED)
    @RegisterRowMapper(SeasonChangeMapper.class)
    @Nullable
    SeasonChange setMode(@Bind("mode") String mode);

    /** Returns the newest time maintenance was turned on, and when it was turned off after it. */
    @SqlQuery("""
            SELECT turned_on.occurred AS turned_on,
                   turned_on.actor_kind,
                   turned_on.actor_id,
                   (SELECT min(later.occurred)
                    FROM journal_entry later
                             JOIN journal_maintenance switch ON switch.entry = later.id
                    WHERE NOT switch.maintenance
                      AND later.occurred > turned_on.occurred) AS turned_off
            FROM journal_entry turned_on
                     JOIN journal_maintenance switch ON switch.entry = turned_on.id
            WHERE switch.maintenance
            ORDER BY turned_on.occurred DESC, turned_on.id DESC
            LIMIT 1
            """)
    @RegisterRowMapper(MaintenanceSpanMapper.class)
    Optional<MaintenanceSpan> lastMaintenance();
}
