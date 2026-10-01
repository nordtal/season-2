package eu.nordtal.s2.steward.auth;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Jdbis;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who is signed in, kept in PostgreSQL so a redeploy signs nobody out.
 *
 * The id is rotated at sign-in against session fixation; this class takes and returns ids and holds no cookie.
 */
public final class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    /** The cookie this service issues, deliberately not {@code JSESSIONID}. */
    public static final String COOKIE = "steward_session";

    /** 256 bits, whose unpadded base64url is safe in a cookie. */
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
        this.dao = Jdbis.over(dataSource).onDemand(SessionDao.class);
    }

    /** Starts a sign-in with a row carrying only Discord's one-time state, and answers the cookie's id. */
    public String begin(final String oauthState) {
        final String id = random();
        dao.begin(id, Objects.requireNonNull(oauthState, "oauthState"), random(), seconds);
        return id;
    }

    /**
     * The state this sign-in started with, readable exactly once.
     *
     * Empty for a missing, expired or already used state alike, on purpose.
     */
    public Optional<String> consumeState(final @Nullable String id) {
        return id == null ? Optional.empty() : dao.consumeState(id);
    }

    /**
     * Records a completed sign-in as a new row, and answers its id.
     *
     * The caller {@link #end}s the row the sign-in started in and replaces the cookie.
     */
    public String signIn(final DiscordId discordId, final String name, final List<String> roles) {
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

    /** Hands this browser a WebAuthn ceremony, replacing any unfinished one; {@code request} is the library's JSON. */
    public void startCeremony(final @Nullable String id, final String request) {
        Objects.requireNonNull(request, "request");
        if (id != null && !id.isBlank()) {
            dao.startCeremony(id, request);
        }
    }

    /** The ceremony this browser started, readable exactly once, empty as in {@link #consumeState}. */
    public Optional<String> consumeCeremony(final @Nullable String id) {
        return id == null || id.isBlank() ? Optional.empty() : dao.consumeCeremony(id);
    }

    /** Records that this browser has just held its key. */
    public void markVerified(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.markVerified(id);
        }
    }

    /** Ends one session, on sign-out or when a sign-in drops the row it started in. */
    public void end(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.end(id);
        }
    }

    /** Signs every browser of one account out, and answers how many. */
    public int endAllOf(final DiscordId discordId) {
        return dao.endAllOf(Objects.requireNonNull(discordId, "discordId"));
    }

    /**
     * Deletes everything past its expiry and answers how many.
     *
     * Housekeeping only: {@link #find} already refuses an expired row.
     */
    public int sweep() {
        final int gone = dao.sweep();
        if (gone > 0) {
            log.info("swept {} expired session(s)", gone);
        }
        return gone;
    }

    /** Moves a session's expiry, for a test. */
    void expireAt(final String id, final Instant at) {
        dao.expireAt(id, at);
    }

    /** Ages the ceremony clock, for a test. */
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
     * {@code roles} is a snapshot of the role ids held at sign-in.
     */
    public record Session(
            String id,
            @ColumnName("discord_id") @Nullable DiscordId discordId,
            @ColumnName("display_name") @Nullable String displayName,
            @Nullable String roles,
            String csrf,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("expires_at") Instant expiresAt,
            @ColumnName("verified_at") @Nullable Instant verifiedAt) {

        /** False while the row is between {@code /auth/login} and a completed callback. */
        public boolean signedIn() {
            return discordId != null && displayName != null;
        }

        public DiscordId signedInDiscordId() {
            return Objects.requireNonNull(discordId, "a signed-in session always carries a discord id");
        }

        public String signedInDisplayName() {
            return Objects.requireNonNull(displayName, "a signed-in session always carries a display name");
        }

        public DiscordAuth.Account account() {
            return new DiscordAuth.Account(
                    Objects.requireNonNull(discordId).value(), Objects.requireNonNull(displayName), roleList());
        }

        /** Whether a security key has been held in this session at all, which is the door rather than the step-up. */
        public boolean verified() {
            return verifiedAt != null;
        }

        public List<String> roleList() {
            return roles == null || roles.isBlank() ? List.of() : List.of(roles.split(","));
        }
    }
}
