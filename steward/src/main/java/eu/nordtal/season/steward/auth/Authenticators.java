package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.steward.data.Data;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jspecify.annotations.Nullable;

/**
 * The authenticator apps: one per account, its secret sealed, each code taken once, wrong codes rate limited.
 *
 * Without a {@link SecretBox} none can be added or used, so an installation without the key offers none.
 */
public final class Authenticators {

    /** Wrong codes allowed within {@link #WRONG_WINDOW}; the next code, right or wrong, waits until it ends. */
    static final int MOST_WRONG = 5;

    static final Duration WRONG_WINDOW = Duration.ofMinutes(15);

    private final AuthenticatorDao dao;
    private final @Nullable SecretBox box;
    private final Clock clock;

    /** Over steward's database, sealing with the hex {@code key}; null without a database, unoffered without a key. */
    public static @Nullable Authenticators over(final @Nullable Data data, final String key, final Clock clock) {
        return data == null
                ? null
                : new Authenticators(data.dataSource(), SecretBox.of(key).orElse(null), clock);
    }

    public Authenticators(final DataSource dataSource, final @Nullable SecretBox box, final Clock clock) {
        this.dao = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource")).onDemand(AuthenticatorDao.class);
        this.box = box;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether this installation can add or check an app at all. */
    public boolean offered() {
        return box != null;
    }

    /** The confirmed app of an account; an enrolment not yet confirmed is not one. */
    public Optional<Row> of(final DiscordId discordId) {
        return find(discordId).filter(row -> row.confirmedAt() != null);
    }

    public boolean confirmed(final DiscordId discordId) {
        return of(discordId).isPresent();
    }

    /** Starts an enrolment with a fresh secret and answers it, or empty when the account already has an app. */
    public Optional<byte[]> enrol(final DiscordId discordId) {
        final byte[] secret = Totp.newSecret();
        return dao.enrol(discordId, box().seal(secret, contextOf(discordId))) == 1
                ? Optional.of(secret)
                : Optional.empty();
    }

    /** What became of one typed code. */
    public enum Outcome {
        ACCEPTED,
        /** Not this app's code for now, or a step either side; counted towards the limit. */
        WRONG,
        /** The code of a step already taken, or of one before it. */
        TAKEN,
        /** Too many wrong codes lately; nothing was checked. */
        LOCKED,
        /** No app, or no enrolment, of the kind asked for. */
        NONE
    }

    /** One checked code, and until when the account is locked out if it is. */
    public record Checked(Outcome outcome, @Nullable Instant lockedUntil) {}

    /**
     * Checks a typed code against the enrolment ({@code confirming}) or the confirmed app, and takes it if it fits.
     *
     * Taking it is one guarded update, so the same code sent twice at once still counts once.
     */
    public Checked check(final DiscordId discordId, final String code, final boolean confirming) {
        final Optional<Row> found = find(discordId).filter(row -> (row.confirmedAt() == null) == confirming);
        if (found.isEmpty()) {
            return new Checked(Outcome.NONE, null);
        }
        final Row row = found.get();
        if (row.lockedUntil() != null) {
            return new Checked(Outcome.LOCKED, row.lockedUntil());
        }
        final byte[] secret = box().open(row.secret(), contextOf(discordId));
        final OptionalLong step = Totp.matching(secret, code, Totp.stepAt(clock.instant()));
        if (step.isEmpty()) {
            dao.wrong(discordId, WRONG_WINDOW.toSeconds());
            return new Checked(Outcome.WRONG, null);
        }
        return dao.take(discordId, step.getAsLong(), confirming) == 1
                ? new Checked(Outcome.ACCEPTED, null)
                : new Checked(Outcome.TAKEN, null);
    }

    /** Removes the confirmed app; answers whether there was one. */
    public boolean remove(final DiscordId discordId) {
        return dao.remove(discordId) > 0;
    }

    /** Removes the app and any enrolment, for {@code forget-factors}; answers how many rows went. */
    public int forget(final DiscordId discordId) {
        return dao.forget(discordId);
    }

    private Optional<Row> find(final DiscordId discordId) {
        return dao.find(discordId, MOST_WRONG, WRONG_WINDOW.toSeconds());
    }

    private SecretBox box() {
        return Objects.requireNonNull(box, "no authenticator key - this route is not available without one");
    }

    /** What a sealed secret is bound to: its own account, so a row copied to another does not open. */
    private static byte[] contextOf(final DiscordId discordId) {
        return ("steward_authenticator:" + discordId.value()).getBytes(StandardCharsets.UTF_8);
    }

    /** One row of {@code steward_authenticator}; {@code lockedUntil} is set while wrong codes lock it. */
    public record Row(
            byte[] secret,
            @ColumnName("confirmed_at") @Nullable Instant confirmedAt,
            @ColumnName("last_step") @Nullable Long lastStep,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("last_used_at") @Nullable Instant lastUsedAt,
            @ColumnName("locked_until") @Nullable Instant lockedUntil) {}
}
