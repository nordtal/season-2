package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores sign-in sessions under the SHA-256 of the cookie's id. */
public final class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    public static final String COOKIE = "steward_session";

    public static final Duration SIGN_IN_WINDOW = Duration.ofMinutes(10);

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

    /** Creates a row that holds only the OAuth state and returns the cookie's id. */
    public String begin(final String oauthState) {
        final String id = random();
        dao.begin(stored(id), Objects.requireNonNull(oauthState, "oauthState"), random(), SIGN_IN_WINDOW.toSeconds());
        return id;
    }

    /** Missing, expired and used states return empty. */
    public Optional<String> consumeState(final @Nullable String id) {
        return id == null ? Optional.empty() : dao.consumeState(stored(id));
    }

    /** The caller {@link #end}s the row of {@link #begin}. */
    public String signIn(final DiscordId discordId, final String name) {
        final String id = random();
        dao.signIn(
                stored(id),
                Objects.requireNonNull(discordId, "discordId"),
                Objects.requireNonNull(name, "name"),
                random(),
                seconds);
        log.info("signed in {} ({}) - session valid for {} days", name, discordId, seconds / 86400);
        return id;
    }

    /** Returns the session for {@code id}, or empty if it is missing, expired or not signed in. */
    public Optional<Session> find(final @Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return dao.find(stored(id), id).filter(Session::signedIn);
    }

    public void startCeremony(final @Nullable String id, final String request) {
        Objects.requireNonNull(request, "request");
        if (id != null && !id.isBlank()) {
            dao.startCeremony(stored(id), request);
        }
    }

    public Optional<String> consumeCeremony(final @Nullable String id) {
        return id == null || id.isBlank() ? Optional.empty() : dao.consumeCeremony(stored(id));
    }

    public void markVerified(final @Nullable String id, final Duration hold) {
        if (id != null && !id.isBlank()) {
            dao.markVerified(stored(id), hold.toSeconds());
        }
    }

    public void endStepUp(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.endStepUp(stored(id));
        }
    }

    public void end(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.end(stored(id));
        }
    }

    public int endAllOf(final DiscordId discordId) {
        return dao.endAllOf(Objects.requireNonNull(discordId, "discordId"));
    }

    public int sweep() {
        final int gone = dao.sweep();
        if (gone > 0) {
            log.info("swept {} expired session(s)", gone);
        }
        return gone;
    }

    /** Sets the expiry of a session, for a test. */
    void expireAt(final String id, final Instant at) {
        dao.expireAt(stored(id), at);
    }

    /** Sets the start time of the ceremony, for a test. */
    void ceremonyStartedAt(final String id, final Instant at) {
        dao.ceremonyStartedAt(stored(id), at);
    }

    static String stored(final String id) {
        return Sha256.hex(id);
    }

    private static String random() {
        final byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** {@code id} is the cookie's id, not the stored hash. */
    public record Session(
            String id,
            @ColumnName("discord_id") @Nullable DiscordId discordId,
            @ColumnName("display_name") @Nullable String displayName,
            String csrf,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("expires_at") Instant expiresAt,
            @ColumnName("verified_at") @Nullable Instant verifiedAt,
            @ColumnName("step_up_until") @Nullable Instant stepUpUntil) {

        /** Returns false for a row of {@link Sessions#begin} that no sign-in has completed. */
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
                    Objects.requireNonNull(discordId).value(), Objects.requireNonNull(displayName));
        }

        /** Returns true when the session has held a key, whatever its step-up state. */
        public boolean verified() {
            return verifiedAt != null;
        }
    }
}
