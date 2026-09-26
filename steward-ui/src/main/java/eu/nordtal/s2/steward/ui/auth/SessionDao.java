package eu.nordtal.s2.steward.ui.auth;

import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

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
    void begin(
            @Bind("id") String id,
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
    void signIn(
            @Bind("id") String id,
            @Bind("discordId") String discordId,
            @Bind("displayName") String displayName,
            @Bind("roles") String roles,
            @Bind("csrf") String csrf,
            @Bind("seconds") long seconds);

    @SqlQuery("""
            SELECT id, discord_id, display_name, roles, csrf, created_at, expires_at, verified_at
            FROM steward_session
            WHERE id = :id
              AND expires_at > now()
            """)
    Optional<Sessions.Session> find(@Bind("id") String id);

    /**
     * Parks the WebAuthn ceremony this browser has just been handed.
     *
     * <p>One at a time, deliberately: a second {@code /start} overwrites the first, so a person who
     * taps the button twice finishes the ceremony they are actually looking at. Two challenges
     * outstanding would mean the older one is redeemable by whoever else has seen it.</p>
     */
    @SqlUpdate("""
            UPDATE steward_session
            SET webauthn_request = :request,
                webauthn_started_at = now()
            WHERE id = :id
              AND expires_at > now()
            """)
    int startCeremony(@Bind("id") String id, @Bind("request") String request);

    /**
     * The ceremony this browser started, readable exactly once.
     *
     * <p><b>The same shape as {@link #consumeState}, and the same trap.</b> {@code RETURNING}
     * answers with the new row, so the obvious one-liner hands back the {@code NULL} it just
     * wrote - see that method for what that cost. The old value comes out of a {@code FOR UPDATE}
     * subquery here too.</p>
     *
     * <p>Reading it once is what makes a challenge single-use: a replayed {@code /finish} finds
     * nothing to verify against and is refused, rather than being checked a second time against a
     * challenge that is still lying there.</p>
     *
     * <p>Ten minutes, and the clock is in the {@code WHERE} rather than in a sweep - same argument
     * as the expiry above. The browser's own dialog gives two minutes, so this only ever catches a
     * ceremony nobody is still looking at.</p>
     */
    @SqlQuery("""
            UPDATE steward_session AS s
            SET webauthn_request = NULL,
                webauthn_started_at = NULL
            FROM (
                SELECT id, webauthn_request
                FROM steward_session
                WHERE id = :id
                FOR UPDATE
            ) AS before
            WHERE s.id = before.id
              AND s.webauthn_request IS NOT NULL
              AND s.webauthn_started_at > now() - interval '10 minutes'
              AND s.expires_at > now()
            RETURNING before.webauthn_request
            """)
    Optional<String> consumeCeremony(@Bind("id") String id);

    /**
     * Records that this browser has just held its key.
     *
     * <p>{@code now()} is PostgreSQL's, like every other clock in this file: the five-minute window
     * of the step-up is compared against the same clock that wrote this, not against whatever the
     * JVM thinks the time is.</p>
     */
    @SqlUpdate("""
            UPDATE steward_session
            SET verified_at = now()
            WHERE id = :id
              AND expires_at > now()
            """)
    int markVerified(@Bind("id") String id);

    @SqlUpdate("DELETE FROM steward_session WHERE id = :id")
    void end(@Bind("id") String id);

    /** Housekeeping. Returns how many rows went, so the log line can be about something. */
    /**
     * Every session of one account, gone.
     *
     * <p>Half of {@code forget-factors}, and the half that is easy to leave out: clearing the keys
     * of an account whose browser is still signed in would leave that browser signed in with no
     * key - which is the state the whole door exists to refuse, reached from the inside.</p>
     *
     * @return how many browsers were signed out
     */
    @SqlUpdate("DELETE FROM steward_session WHERE discord_id = :discordId")
    int endAllOf(@Bind("discordId") String discordId);

    @SqlUpdate("DELETE FROM steward_session WHERE expires_at < now()")
    int sweep();

    /**
     * Only for the tests that have to age a session without waiting for one.
     *
     * <p>It is here rather than in a test helper because a test that writes its own SQL against
     * this table is a second place that has to be changed when a column moves, and the first thing
     * such a test stops noticing is a column it no longer writes.</p>
     *
     * <p><b>It moves {@code created_at} as well, and it has to.</b> V19 constrains
     * {@code expires_at > created_at}, so the one-line version of this - setting the expiry alone -
     * cannot age a session at all: PostgreSQL refuses it. That went unnoticed because this method
     * was written in the same commit as the constraint and had no caller until now, which is its
     * own small lesson about a helper added ahead of the test that needs it.</p>
     */
    @SqlUpdate("""
            UPDATE steward_session
            SET expires_at = CAST(:at AS timestamptz),
                created_at = LEAST(created_at,
                                   CAST(:at AS timestamptz) - interval '1 second')
            WHERE id = :id
            """)
    void expireAt(@Bind("id") String id, @Bind("at") Instant at);

    /** The same, for the ceremony clock: a challenge aged past its window without a ten-minute wait. */
    @SqlUpdate("UPDATE steward_session SET webauthn_started_at = :at WHERE id = :id")
    void ceremonyStartedAt(@Bind("id") String id, @Bind("at") Instant at);
}
