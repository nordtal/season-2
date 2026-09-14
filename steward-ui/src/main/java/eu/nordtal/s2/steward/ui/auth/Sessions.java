package eu.nordtal.s2.steward.ui.auth;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Who is signed in, kept in PostgreSQL rather than in this JVM's memory.
 *
 * <h2>Why this exists at all</h2>
 * Jetty's servlet session did the job and had one property nobody chose: a restart of this
 * container signed everybody out. For a service that is redeployed on every release - and read
 * mostly from a phone, where the sign-in is three redirects through discord.com - that turned
 * ordinary operations into a chore. {@code V19} carries the rest of the reasoning, including why
 * Jetty's own JDBC session store was not the answer.
 *
 * <h2>The id is rotated at sign-in, and that is not tidiness</h2>
 * A row exists before anybody is signed in, because the OAuth state has to live somewhere between
 * {@code /auth/login} and {@code /auth/callback}. If that row were then simply filled in, the
 * identifier the browser was carrying before the sign-in would still be valid after it - which is
 * session fixation: anybody who can get a cookie value into somebody else's browser (a shared
 * machine, an XSS anywhere under {@code nordtal.eu}, a subdomain setting a cookie for the parent)
 * is signed in as them the moment they sign in. So {@link #signIn} writes a <em>new</em> row with a
 * new id and the caller drops the old one. It costs one insert and one delete.
 *
 * <h2>This class holds no cookie</h2>
 * It takes and returns ids. Where that id lives in a request - the cookie, its flags, its lifetime
 * - belongs to the web layer and is spelled out in {@code StewardUi}, because those flags are about
 * HTTP and this is about rows.
 */
public final class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    /**
     * The cookie this service issues.
     *
     * <p>Deliberately not {@code JSESSIONID}: nothing here is a servlet session any more, and a
     * name that says otherwise sends the next person reading a {@code Set-Cookie} header to look
     * for a session handler that is no longer there.</p>
     */
    public static final String COOKIE = "steward_session";

    /**
     * 256 bits, which is the number the whole of this table's security rests on.
     *
     * <p>Base64url of 32 bytes is 43 characters and needs no padding, so the value is safe in a
     * cookie without escaping.</p>
     */
    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SessionDao dao;
    private final long seconds;

    public Sessions(final @NotNull DataSource dataSource, final @NotNull Duration lifetime) {
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
    public @NotNull String begin(final @NotNull String oauthState) {
        final String id = random();
        dao.begin(id, Objects.requireNonNull(oauthState, "oauthState"), random(), seconds);
        return id;
    }

    /**
     * The state this sign-in started with, readable exactly once.
     *
     * <p>Empty means there is nothing to match: no such row, the row has expired, or the state has
     * already been used. All three answer the same way on purpose - the callback's message is "this
     * sign-in did not start in this browser", and distinguishing the three for the caller would be
     * telling whoever sent the callback which of their guesses was closest.</p>
     */
    public @NotNull Optional<String> consumeState(final @Nullable String id) {
        return id == null ? Optional.empty() : dao.consumeState(id);
    }

    /**
     * Records a completed sign-in as a new row, and answers its id.
     *
     * <p>The caller is expected to {@link #end} the row the sign-in started in and to replace the
     * browser's cookie with what this returns. See the class note on fixation.</p>
     */
    public @NotNull String signIn(final @NotNull String discordId, final @NotNull String name,
                                  final @NotNull List<String> roles) {
        final String id = random();
        dao.signIn(id, Objects.requireNonNull(discordId, "discordId"),
                Objects.requireNonNull(name, "name"), String.join(",", roles), random(), seconds);
        log.info("signed in {} ({}) - session valid for {} days", name, discordId, seconds / 86400);
        return id;
    }

    /** The session behind an id, or empty if there is none, it has expired, or it is unfinished. */
    public @NotNull Optional<Session> find(final @Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return dao.find(id).filter(Session::signedIn);
    }

    /** Ends one session. Used by sign-out, and by the sign-in dropping the row it started in. */
    public void end(final @Nullable String id) {
        if (id != null && !id.isBlank()) {
            dao.end(id);
        }
    }

    /**
     * Deletes everything past its expiry.
     *
     * <p>Housekeeping only: {@link #find} refuses an expired row whether this has run or not. What
     * it is actually for is the rows nobody ever comes back for - a sign-in started and abandoned
     * at Discord leaves a row that no lookup will ever visit again.</p>
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
    public record Session(@NotNull String id,
                          @ColumnName("discord_id") @Nullable String discordId,
                          @ColumnName("display_name") @Nullable String displayName,
                          @Nullable String roles,
                          @NotNull String csrf,
                          @ColumnName("created_at") @NotNull Instant createdAt,
                          @ColumnName("expires_at") @NotNull Instant expiresAt) {

        /** False for a row that is still between {@code /auth/login} and a completed callback. */
        public boolean signedIn() {
            return discordId != null && displayName != null;
        }

        /** The account, for everything that was written against Discord's answer directly. */
        public DiscordAuth.@NotNull Account account() {
            return new DiscordAuth.Account(Objects.requireNonNull(discordId),
                    Objects.requireNonNull(displayName), roleList());
        }

        public @NotNull List<String> roleList() {
            return roles == null || roles.isBlank() ? List.of() : List.of(roles.split(","));
        }
    }
}
