package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** Holds the SQL of {@link Authenticators}. */
@RegisterConstructorMapper(Authenticators.Row.class)
public interface AuthenticatorDao {

    /** Returns 0 when the account has a confirmed app. */
    @SqlUpdate("""
            INSERT INTO steward_authenticator (discord_id, secret, created_at)
            VALUES (:discordId, :secret, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET secret = excluded.secret,
                    created_at = now(),
                    last_step = NULL,
                    wrong_codes = 0,
                    wrong_since = NULL
                WHERE steward_authenticator.confirmed_at IS NULL
            """)
    int enrol(@Bind("discordId") DiscordId discordId, @Bind("secret") byte[] secret);

    @SqlQuery("""
            SELECT secret, confirmed_at, last_step, created_at, last_used_at,
                   CASE WHEN wrong_codes >= :most AND wrong_since > now() - make_interval(secs => :seconds)
                        THEN wrong_since + make_interval(secs => :seconds) END AS locked_until
            FROM steward_authenticator
            WHERE discord_id = :discordId
            """)
    Optional<Authenticators.Row> find(
            @Bind("discordId") DiscordId discordId, @Bind("most") int most, @Bind("seconds") long seconds);

    /**
     * Records the step of an accepted code. Returns 0 when that step or a later one was recorded.
     *
     * {@code confirming} selects the unconfirmed enrolment (true) or the confirmed app (false).
     */
    @SqlUpdate("""
            UPDATE steward_authenticator
            SET last_step = :step,
                wrong_codes = 0,
                wrong_since = NULL,
                confirmed_at = CASE WHEN :confirming THEN now() ELSE confirmed_at END,
                last_used_at = CASE WHEN :confirming THEN last_used_at ELSE now() END
            WHERE discord_id = :discordId
              AND (confirmed_at IS NULL) = :confirming
              AND (last_step IS NULL OR last_step < :step)
            """)
    int take(@Bind("discordId") DiscordId discordId, @Bind("step") long step, @Bind("confirming") boolean confirming);

    @SqlUpdate("""
            UPDATE steward_authenticator
            SET wrong_codes = CASE WHEN wrong_since > now() - make_interval(secs => :seconds)
                                   THEN wrong_codes + 1 ELSE 1 END,
                wrong_since = CASE WHEN wrong_since > now() - make_interval(secs => :seconds)
                                   THEN wrong_since ELSE now() END
            WHERE discord_id = :discordId
            """)
    void wrong(@Bind("discordId") DiscordId discordId, @Bind("seconds") long seconds);

    @SqlUpdate("DELETE FROM steward_authenticator WHERE discord_id = :discordId AND confirmed_at IS NOT NULL")
    int remove(@Bind("discordId") DiscordId discordId);

    /** Deletes the row of the account, an enrolment included. */
    @SqlUpdate("DELETE FROM steward_authenticator WHERE discord_id = :discordId")
    int forget(@Bind("discordId") DiscordId discordId);
}
