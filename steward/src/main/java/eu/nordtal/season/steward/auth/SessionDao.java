package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** Every {@code id} here is the stored hash. */
@RegisterConstructorMapper(Sessions.Session.class)
public interface SessionDao {

    @SqlUpdate("""
            INSERT INTO steward_session (id, oauth_state, csrf, created_at, expires_at)
            VALUES (:id, :state, :csrf, now(), now() + make_interval(secs => :seconds))
            """)
    void begin(
            @Bind("id") String id,
            @Bind("state") String state,
            @Bind("csrf") String csrf,
            @Bind("seconds") long seconds);

    /** Returns the OAuth state and sets it to NULL in the same statement. The self-join returns the value from before the update. */
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

    @SqlUpdate("""
            INSERT INTO steward_session
                (id, discord_id, display_name, csrf, created_at, expires_at)
            VALUES (:id, :discordId, :displayName, :csrf, now(),
                    now() + make_interval(secs => :seconds))
            """)
    void signIn(
            @Bind("id") String id,
            @Bind("discordId") DiscordId discordId,
            @Bind("displayName") String displayName,
            @Bind("csrf") String csrf,
            @Bind("seconds") long seconds);

    /** Returns the row stored under {@code id} with {@code cookie} as its id, since the table holds only the hash. */
    @SqlQuery("""
            SELECT :cookie AS id, discord_id, display_name, csrf, created_at, expires_at, verified_at,
                   step_up_until
            FROM steward_session
            WHERE id = :id
              AND expires_at > now()
            """)
    Optional<Sessions.Session> find(@Bind("id") String id, @Bind("cookie") String cookie);

    @SqlUpdate("""
            UPDATE steward_session
            SET webauthn_request = :request,
                webauthn_started_at = now()
            WHERE id = :id
              AND expires_at > now()
            """)
    int startCeremony(@Bind("id") String id, @Bind("request") String request);

    /** Returns the stored WebAuthn request once, within ten minutes of its start, as {@link #consumeState} does for the state. */
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

    @SqlUpdate("""
            UPDATE steward_session
            SET verified_at = now(),
                step_up_until = now() + make_interval(secs => :seconds)
            WHERE id = :id
              AND expires_at > now()
            """)
    int markVerified(@Bind("id") String id, @Bind("seconds") long seconds);

    /** {@code verified_at} stays. */
    @SqlUpdate("UPDATE steward_session SET step_up_until = NULL WHERE id = :id")
    int endStepUp(@Bind("id") String id);

    @SqlUpdate("DELETE FROM steward_session WHERE id = :id")
    void end(@Bind("id") String id);

    @SqlUpdate("DELETE FROM steward_session WHERE discord_id = :discordId")
    int endAllOf(@Bind("discordId") DiscordId discordId);

    @SqlUpdate("DELETE FROM steward_session WHERE expires_at < now()")
    int sweep();

    /** Lowers {@code created_at} too, as a check constraint requires. */
    @SqlUpdate("""
            UPDATE steward_session
            SET expires_at = CAST(:at AS timestamptz),
                created_at = LEAST(created_at,
                                   CAST(:at AS timestamptz) - interval '1 second')
            WHERE id = :id
            """)
    void expireAt(@Bind("id") String id, @Bind("at") Instant at);

    @SqlUpdate("UPDATE steward_session SET webauthn_started_at = :at WHERE id = :id")
    void ceremonyStartedAt(@Bind("id") String id, @Bind("at") Instant at);
}
