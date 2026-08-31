package eu.nordtal.s2.discordbot.payment;

import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The cut-off the payment poll applies: payments created before it are ignored, completely and
 * forever.
 *
 * <h2>It decides itself, once</h2>
 * The first start that finds no stored value writes the instant of that start. That is the only
 * cut-off that is correct by construction - a date written into a config file in advance is either
 * too early, in which case the first poll books up to fifty historical payments on the bunq account
 * with the grants, roles, DMs and public thank-yous that go with them, or too late, in which case
 * real purchases in the gap are silently ignored.
 * <p>
 * The write is {@code ON CONFLICT DO NOTHING} and there is no code path that updates the row, so
 * two containers starting together agree on whichever got there first, and a later start never
 * moves the cut-off forward.
 * </p>
 *
 * <h2>The override does not replace it</h2>
 * {@code payment.watermark} in {@code access.yml} wins when it is set, but the stored value is
 * still written if it is missing. Emptying the override therefore falls back to the original
 * first-start instant rather than to whenever the bot happened to restart.
 */
@Slf4j
public final class Watermark {

    private static final String KEY = "payment.watermark";

    private Watermark() {
    }

    /**
     * Resolves the cut-off, writing the first-start value if there is none yet.
     *
     * @param jdbi       the bot's database
     * @param configured {@code payment.watermark} from {@code access.yml}; blank for the normal
     *                   case
     * @return the instant before which payments are ignored
     */
    public static Instant resolve(final Jdbi jdbi, final String configured) {
        final BotSettingDao dao = jdbi.onDemand(BotSettingDao.class);

        // Written even when an override is set, so removing the override later falls back to the
        // first start rather than to now.
        final Instant now = Instant.now();
        if (dao.insertIfAbsent(KEY, now.toString()) == 1) {
            log.info("No payment watermark was stored; this start is the cut-off: {}. "
                    + "Payments created before it are ignored forever.", now);
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

    /** @return when the stored watermark was first written, for {@code /access-status}-style output */
    public static Optional<Instant> storedAt(final Jdbi jdbi) {
        return jdbi.onDemand(BotSettingDao.class).createdAt(KEY).map(OffsetDateTime::toInstant);
    }

    /** {@code bot_setting}: values the bot decides once and must never decide again. */
    interface BotSettingDao {

        /**
         * @return 1 when this call wrote the value, 0 when it was already there - which is what
         *         makes "written exactly once" true across two containers rather than hoped for
         */
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

    /** @return the instant as {@code timestamptz} would see it, for tests and logging */
    static OffsetDateTime utc(final Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
