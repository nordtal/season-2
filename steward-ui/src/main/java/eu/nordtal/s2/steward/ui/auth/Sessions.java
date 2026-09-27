package eu.nordtal.s2.steward.ui.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who is signed in, kept in PostgreSQL rather than this JVM's memory.
 *
 * A redeploy does not sign everybody out.
 *
 * The id is rotated at sign-in rather than reused, to avoid session fixation: {@link #signIn}
 * writes a new row with a new id and the caller drops the old one. This class holds no cookie - it
 * takes and returns ids; the cookie itself belongs to the web layer.
 */
public final class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    /** The cookie this service issues; deliberately not {@code JSESSIONID}, since this is not a servlet session. */
    public static final String COOKIE = "steward_session";

    /** 256 bits; base64url of 32 bytes is 43 characters and needs no padding, so it is safe in a cookie. */
    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SessionDao dao;
    private final long seconds;

    public Sessions(final DataSource dataSource, final Duration lifetime) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.seconds = Objects.requireNonNull(lifetime, "lifetime").toSeconds();
        if (seconds <= 0) {
            throw new IllegalArgumentException("a session lifetime of " + lifetime
                    + " would sign everybody out on the redirect that signed them in");
        }
        this.dao = Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin())
                .onDemand(SessionDao.class);
    }

    /**
     * Starts a sign-in: a row carrying only the one-time state Discord will hand back.
     *
     * @return the id to put in the browser's cookie
     */
    public String begin(final String oauthState) {
        final String id = random();
        dao.begin(id, Objects.requireNonNull(oauthState, "oauthState"), random(), seconds);
        return id;
    }

    /**
     * The state this sign-in started with, readable exactly once.
     *
     * Empty means there is nothing to match - no such row, an expired one, or an already-used
     * state - and all three answer the same way on purpose.
     */
    public Optional<String> consumeState(final @Nullable String id) {
        return id == null ? Optional.empty() : dao.consumeState(id);
    }

    /**
     * Records a completed sign-in as a new row, and answers its id.
     *
     * The caller is expected to {@link #end} the row the sign-in started in and replace the
     * browser's cookie with what this returns.
     */
    public String signIn(final String discordId, final String name, final List<String> roles) {
        final String id = random();
        dao.signIn(
                id,
                Objects.requireNonNull(discordId, "discordId"),
                Objects.requireNonNull(name, "name"),
                String.join(",", roles),
                random(),
                seconds);
        log.info("signed in {} ({}) - session valid for {} days", name, discordId, seconds / 86400);
        return id;
    }

    /** The session behind an id, or empty if there is none, it has expired, or it is unfinished. */
    public Optional<Session> find(final @Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return dao.find(id).filter(Session::signedIn);
    }

    /**
     * Hands this browser a WebAuthn ceremony to answer, replacing any it had not finished.
     *
     * @param request the library's own JSON - see {@code WebAuthn}, the only class that reads it
     */
    public void startCeremony(final @Nullable String id, final String request) {
        Objects.requireNonNull(request, "request");
        if (id != null && !id.isBlank()) {
            dao.startCeremony(id, request);
        }
    }

    /**
     * The ceremony this browser started, readable exactly once.
     *
     * Empty means there is nothing to finish - none started, already answered, or the session is
     * gone - the same as {@link #consumeState}.
     */
    public Optional<String> consumeCeremony(final @Nullable String id) {
        return id == null || id.isBlank() ? Optional.empty() : dao.consumeCeremony(id);
    }

    /** Records that this browser has just proved a key. */
    public void markVerified(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.markVerified(id);
        }
    }

    /** Ends one session. Used by sign-out, and by the sign-in dropping the row it started in. */
    public void end(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.end(id);
        }
    }

    /**
     * Signs every browser of one account out. See {@link SessionDao#endAllOf}.
     *
     * @return how many sessions were ended
     */
    public int endAllOf(final String discordId) {
        return dao.endAllOf(Objects.requireNonNull(discordId, "discordId"));
    }

    /**
     * Deletes everything past its expiry.
     *
     * Housekeeping only: {@link #find} already refuses an expired row. This clears the rows
     * nobody ever comes back for, such as a sign-in abandoned at Discord.
     *
     * @return how many rows went
     */
    public int sweep() {
        final int gone = dao.sweep();
        if (gone > 0) {
            log.info("swept {} expired session(s)", gone);
        }
        return gone;
    }

    /** Only for tests: moves a session's expiry, so one can be aged without waiting for it. */
    void expireAt(final String id, final Instant at) {
        dao.expireAt(id, at);
    }

    /** Only for tests: ages the ceremony clock, so the ten-minute window can be walked past. */
    void ceremonyStartedAt(final String id, final Instant at) {
        dao.ceremonyStartedAt(id, at);
    }

    private static String random() {
        final byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * One row of {@code steward_session}, as everything above the sign-in sees it.
     *
     * @param roles the role ids held at sign-in - a snapshot, see {@code V19}
     */
    public record Session(
            String id,
            @ColumnName("discord_id") @Nullable String discordId,
            @ColumnName("display_name") @Nullable String displayName,
            @Nullable String roles,
            String csrf,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("expires_at") Instant expiresAt,
            @ColumnName("verified_at") @Nullable Instant verifiedAt) {

        /** False for a row that is still between {@code /auth/login} and a completed callback. */
        public boolean signedIn() {
            return discordId != null && displayName != null;
        }

        /** {@link #discordId}, for a session already known to be {@link #signedIn}. */
        public String signedInDiscordId() {
            return Objects.requireNonNull(discordId, "a signed-in session always carries a discord id");
        }

        /** {@link #displayName}, for a session already known to be {@link #signedIn}. */
        public String signedInDisplayName() {
            return Objects.requireNonNull(displayName, "a signed-in session always carries a display name");
        }

        /** The account, for everything that was written against Discord's answer directly. */
        public DiscordAuth.Account account() {
            return new DiscordAuth.Account(
                    Objects.requireNonNull(discordId), Objects.requireNonNull(displayName), roleList());
        }

        /**
         * Whether a security key has been held in this session at all.
         *
         * Not the step-up's question of whether it was held recently - this is the door: a session
         * that never saw a key reaches only the setup page.
         */
        public boolean verified() {
            return verifiedAt != null;
        }

        public List<String> roleList() {
            return roles == null || roles.isBlank() ? List.of() : List.of(roles.split(","));
        }
    }
}
