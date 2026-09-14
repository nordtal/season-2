package eu.nordtal.s2.steward.ui.auth;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;

/**
 * The SQL behind {@link Sessions}. Package-private: {@code Sessions} is the API.
 *
 * <h2>Every read carries its own expiry check</h2>
 * There is a sweep, and it runs once an hour, and it is <em>housekeeping</em> - it keeps the table
 * small and it is allowed to be late. It is deliberately not what makes an expired session stop
 * working: a lookup that trusted the sweep would hand out a valid session for up to an hour after
 * it ended, and would hand one out forever on a deployment whose sweep thread died. So
 * {@code expires_at > now()} is in the {@code WHERE} of the lookup itself, and {@code now()} is
 * PostgreSQL's rather than the JVM's - one clock, not one per container.
 */
@RegisterConstructorMapper(Sessions.Session.class)
interface SessionDao {

    /**
     * The row that exists between {@code /auth/login} and {@code /auth/callback}.
     *
     * <p>No account yet - see the table comment in {@code V19}. It carries a CSRF token from the
     * start anyway, so that the column can be {@code NOT NULL} and so that there is never a moment
     * where a row exists and a write could be accepted without one.</p>
     */
    @SqlUpdate("""
            INSERT INTO steward_session (id, oauth_state, csrf, created_at, expires_at)
            VALUES (:id, :state, :csrf, now(), now() + make_interval(secs => :seconds))
            """)
    void begin(@Bind("id") String id,
               @Bind("state") String state,
               @Bind("csrf") String csrf,
               @Bind("seconds") long seconds);

    /**
     * Reads the one-time OAuth state and clears it in the same statement.
     *
     * <p><b>The clearing is the point, and it has to be atomic.</b> A state that could be read
     * twice is a state a replayed callback matches, so this is a {@code RETURNING} on the
     * {@code UPDATE} and never a {@code SELECT} followed by an {@code UPDATE}. Two callbacks
     * arriving at once therefore have exactly one winner: the second finds
     * {@code oauth_state IS NOT NULL} false and updates nothing.</p>
     *
     * <h2>Why the self-join, when {@code RETURNING oauth_state} reads so much better</h2>
     * Because {@code RETURNING} answers with the <b>new</b> row, and the new row's
     * {@code oauth_state} is the {@code NULL} this statement just wrote. The short version
     * therefore compiled, ran, updated the right row and handed back nothing at all - so every
     * sign-in ended at "this sign-in did not start in this browser". PostgreSQL 18 has
     * {@code RETURNING OLD.oauth_state} for exactly this; the deployment is on 17.11 (measured
     * 2026-09-12), so the old value is carried out of a subquery instead.
     *
     * <p>The subquery takes {@code FOR UPDATE}, which is what keeps it a single winner: without
     * it, two callbacks could both read the old value before either wrote the {@code NULL}.</p>
     */
    @SqlQuery("""
            UPDATE steward_session AS s
            SET oauth_state = NULL
            FROM (
                SELECT id, oauth_state
                FROM steward_session
                WHERE id = :id
                FOR UPDATE
            ) AS before
            WHERE s.id = before.id
              AND s.oauth_state IS NOT NULL
              AND s.expires_at > now()
            RETURNING before.oauth_state
            """)
    Optional<String> consumeState(@Bind("id") String id);

    /** A fresh, signed-in row. The id is new - see {@link Sessions#signIn}. */
    @SqlUpdate("""
            INSERT INTO steward_session
                (id, discord_id, display_name, roles, csrf, created_at, expires_at)
            VALUES (:id, :discordId, :displayName, :roles, :csrf, now(),
                    now() + make_interval(secs => :seconds))
            """)
    void signIn(@Bind("id") String id,
                @Bind("discordId") String discordId,
                @Bind("displayName") String displayName,
                @Bind("roles") String roles,
                @Bind("csrf") String csrf,
                @Bind("seconds") long seconds);

    @SqlQuery("""
            SELECT id, discord_id, display_name, roles, csrf, created_at, expires_at
            FROM steward_session
            WHERE id = :id
              AND expires_at > now()
            """)
    Optional<Sessions.Session> find(@Bind("id") String id);

    @SqlUpdate("DELETE FROM steward_session WHERE id = :id")
    void end(@Bind("id") String id);

    /** Housekeeping. Returns how many rows went, so the log line can be about something. */
    @SqlUpdate("DELETE FROM steward_session WHERE expires_at < now()")
    int sweep();

    /**
     * Only for the tests that have to age a session without waiting for one.
     *
     * <p>It is here rather than in a test helper because a test that writes its own SQL against
     * this table is a second place that has to be changed when a column moves, and the first thing
     * such a test stops noticing is a column it no longer writes.</p>
     */
    @SqlUpdate("UPDATE steward_session SET expires_at = :at WHERE id = :id")
    void expireAt(@Bind("id") String id, @Bind("at") Instant at);
}
