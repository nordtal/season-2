package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The SQL behind {@link Sessions}, which is the API.
 *
 * Every read checks {@code expires_at > now()} itself; the hourly sweep is only housekeeping.
 */
@RegisterConstructorMapper(Sessions.Session.class)
interface SessionDao {

    /** The row between {@code /auth/login} and {@code /auth/callback}, with no account yet. */
    @SqlUpdate("""
            INSERT INTO steward_session (id, oauth_state, csrf, created_at, expires_at)
            VALUES (:id, :state, :csrf, now(), now() + make_interval(secs => :seconds))
            """)
    void begin(
            @Bind("id") String id,
            @Bind("state") String state,
            @Bind("csrf") String csrf,
            @Bind("seconds") long seconds);

    /** Reads the one-time OAuth state and clears it atomically; the self-join returns the old value, not the NULL. */
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

    /** A fresh, signed-in row under a new id; see {@link Sessions#signIn}. */
    @SqlUpdate("""
            INSERT INTO steward_session
                (id, discord_id, display_name, roles, csrf, created_at, expires_at)
            VALUES (:id, :discordId, :displayName, :roles, :csrf, now(),
                    now() + make_interval(secs => :seconds))
            """)
    void signIn(
            @Bind("id") String id,
            @Bind("discordId") DiscordId discordId,
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

    /** Parks the WebAuthn ceremony this browser has just been handed, one at a time. */
    @SqlUpdate("""
            UPDATE steward_session
            SET webauthn_request = :request,
                webauthn_started_at = now()
            WHERE id = :id
              AND expires_at > now()
            """)
    int startCeremony(@Bind("id") String id, @Bind("request") String request);

    /** The ceremony this browser started, readable once within ten minutes, as {@link #consumeState} does it. */
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

    /** Records that this browser has just held its key, on PostgreSQL's clock. */
    @SqlUpdate("""
            UPDATE steward_session
            SET verified_at = now()
            WHERE id = :id
              AND expires_at > now()
            """)
    int markVerified(@Bind("id") String id);

    @SqlUpdate("DELETE FROM steward_session WHERE id = :id")
    void end(@Bind("id") String id);

    /** Ends every session of one account and answers how many, so no session outlives its keys. */
    @SqlUpdate("DELETE FROM steward_session WHERE discord_id = :discordId")
    int endAllOf(@Bind("discordId") DiscordId discordId);

    /** Deletes expired rows and answers how many. */
    @SqlUpdate("DELETE FROM steward_session WHERE expires_at < now()")
    int sweep();

    /** Ages a session for a test, moving {@code created_at} too, which a check constraint requires. */
    @SqlUpdate("""
            UPDATE steward_session
            SET expires_at = CAST(:at AS timestamptz),
                created_at = LEAST(created_at,
                                   CAST(:at AS timestamptz) - interval '1 second')
            WHERE id = :id
            """)
    void expireAt(@Bind("id") String id, @Bind("at") Instant at);

    /** Ages the ceremony clock for a test. */
    @SqlUpdate("UPDATE steward_session SET webauthn_started_at = :at WHERE id = :id")
    void ceremonyStartedAt(@Bind("id") String id, @Bind("at") Instant at);
}
