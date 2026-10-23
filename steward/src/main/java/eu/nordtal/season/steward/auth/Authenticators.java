package eu.nordtal.season.steward.auth;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
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

/** Stores one authenticator app per account with a sealed secret. Each code is accepted once. */
public final class Authenticators {

    /** Locks the account at this many wrong codes within {@link #WRONG_WINDOW}. */
    static final int MOST_WRONG = 5;

    static final Duration WRONG_WINDOW = Duration.ofMinutes(15);

    private final AuthenticatorDao dao;
    private final @Nullable SecretBox box;
    private final Clock clock;

    public Authenticators(final DataSource dataSource, final @Nullable SecretBox box, final Clock clock) {
        this.dao = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource")).onDemand(AuthenticatorDao.class);
        this.box = box;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean offered() {
        return box != null;
    }

    /** An unconfirmed enrolment is not an app. */
    public Optional<Row> of(final DiscordId discordId) {
        return find(discordId).filter(row -> row.confirmedAt() != null);
    }

    public boolean confirmed(final DiscordId discordId) {
        return of(discordId).isPresent();
    }

    public Optional<byte[]> enrol(final DiscordId discordId) {
        final byte[] secret = Totp.newSecret();
        return dao.enrol(discordId, box().seal(secret, contextOf(discordId))) == 1
                ? Optional.of(secret)
                : Optional.empty();
    }

    public enum Outcome {
        ACCEPTED,
        /** The code matches no accepted step. It counts towards the limit. */
        WRONG,
        /** The step of the code, or a later step, was used already. */
        TAKEN,
        /** Too many wrong codes. The code was not checked. */
        LOCKED,
        /** No row of the requested kind exists. */
        NONE
    }

    public record Checked(Outcome outcome, @Nullable Instant lockedUntil) {}

    /**
     * Checks {@code code} against the enrolment ({@code confirming}) or the confirmed app.
     * A match records its step.
     * The recording is one guarded update, so a code sent twice counts once.
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

    public boolean remove(final DiscordId discordId) {
        return dao.remove(discordId) > 0;
    }

    public int forget(final DiscordId discordId) {
        return dao.forget(discordId);
    }

    private Optional<Row> find(final DiscordId discordId) {
        return dao.find(discordId, MOST_WRONG, WRONG_WINDOW.toSeconds());
    }

    private SecretBox box() {
        return Objects.requireNonNull(box, "no authenticator key - this route is not available without one");
    }

    /** A row copied to another account fails to open. */
    private static byte[] contextOf(final DiscordId discordId) {
        return ("steward_authenticator:" + discordId.value()).getBytes(StandardCharsets.UTF_8);
    }

    /** {@code lockedUntil} is set while wrong codes lock the account. */
    public record Row(
            byte[] secret,
            @ColumnName("confirmed_at") @Nullable Instant confirmedAt,
            @ColumnName("last_step") @Nullable Long lastStep,
            @ColumnName("created_at") Instant createdAt,
            @ColumnName("last_used_at") @Nullable Instant lastUsedAt,
            @ColumnName("locked_until") @Nullable Instant lockedUntil) {}
}
