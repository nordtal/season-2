package eu.nordtal.s2.common.payment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cut-off before which the payment poll ignores payments, written once by the first start.
 * The {@code payment.watermark} override wins when set, but the stored value is still written.
 */
public final class Watermark {

    private static final Logger log = LoggerFactory.getLogger(Watermark.class);

    private static final String KEY = "payment.watermark";

    private Watermark() {}

    /**
     * Resolves the cut-off, writing the first-start value if there is none yet.
     *
     * @param configured {@code payment.watermark} from {@code access.yml}, normally blank
     */
    public static Instant resolve(final Jdbi jdbi, final String configured) {
        final BotSettingDao dao = jdbi.onDemand(BotSettingDao.class);

        // Written even with an override, so removing it later falls back to the first start.
        final Instant now = Instant.now();
        if (dao.insertIfAbsent(KEY, now.toString()) == 1) {
            log.info(
                    "No payment watermark was stored; this start is the cut-off: {}. "
                            + "Payments created before it are ignored forever.",
                    now);
        }

        if (configured != null && !configured.isBlank()) {
            final Instant override = Instant.parse(configured.trim());
            log.info("Using the payment watermark from access.yml: {}", override);
            return override;
        }

        final Optional<String> stored = dao.value(KEY);
        if (stored.isEmpty()) {
            // Only reachable if the row vanished between the insert and this read.
            throw new IllegalStateException("The payment watermark could not be read back after being "
                    + "written. Refusing to poll bunq without a cut-off.");
        }
        final Instant watermark = Instant.parse(stored.get());
        log.info("Payment watermark: {}", watermark);
        return watermark;
    }

    /** Returns when the stored watermark was first written. */
    public static Optional<Instant> storedAt(final Jdbi jdbi) {
        return jdbi.onDemand(BotSettingDao.class).createdAt(KEY).map(OffsetDateTime::toInstant);
    }

    /** {@code bot_setting}: values the bot decides once and never again. */
    interface BotSettingDao {

        /** Returns 1 when this call wrote the value, 0 when it was already there. */
        @SqlUpdate("""
                INSERT INTO bot_setting (key, value)
                VALUES (:key, :value)
                ON CONFLICT (key) DO NOTHING
                """)
        int insertIfAbsent(@Bind("key") String key, @Bind("value") String value);

        @SqlQuery("SELECT value FROM bot_setting WHERE key = :key")
        Optional<String> value(@Bind("key") String key);

        @SqlQuery("SELECT created FROM bot_setting WHERE key = :key")
        Optional<OffsetDateTime> createdAt(@Bind("key") String key);
    }

    /** Returns the instant as {@code timestamptz} would see it. */
    static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
