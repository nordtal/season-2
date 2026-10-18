package eu.nordtal.season.database.payment;

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
 * The time before which the payment poll ignores payments. The first start writes it.
 * The {@code payment.watermark} override wins, but the stored value is still written.
 */
public final class Watermark {

    private static final Logger log = LoggerFactory.getLogger(Watermark.class);

    private Watermark() {}

    /**
     * Returns the cut-off and writes the first-start value if none is stored.
     *
     * @param configured {@code payment.watermark} of the {@code access} group, normally blank
     * @param now this start, the cut-off if none is stored
     */
    public static Instant resolve(final Jdbi jdbi, final String configured, final Instant now) {
        final WatermarkDao dao = jdbi.onDemand(WatermarkDao.class);

        // Written even with an override, so removing it falls back to the first start.
        if (dao.writeIfAbsent(now.atOffset(ZoneOffset.UTC)) == 1) {
            log.info(
                    "No payment watermark was stored; this start is the cut-off: {}. "
                            + "Payments created before it are ignored forever.",
                    now);
        }

        if (configured != null && !configured.isBlank()) {
            final Instant override = Instant.parse(configured.trim());
            log.info("Using the payment watermark from the access group: {}", override);
            return override;
        }

        final Instant watermark = stored(jdbi)
                .orElseThrow(() -> new IllegalStateException("The payment watermark could not be read back after"
                        + " being written. Refusing to poll bunq without a cut-off."));
        log.info("Payment watermark: {}", watermark);
        return watermark;
    }

    /** Returns the stored cut-off, empty until a start that polls wrote it. */
    public static Optional<Instant> stored(final Jdbi jdbi) {
        return jdbi.onDemand(WatermarkDao.class).watermark().map(OffsetDateTime::toInstant);
    }

    /** The {@code watermark} column of the one {@code payment_gateway} row. */
    interface WatermarkDao {

        /** Returns 1 if this call wrote the value, 0 if one existed. */
        @SqlUpdate("""
                INSERT INTO payment_gateway (watermark)
                VALUES (:watermark)
                ON CONFLICT (id) DO UPDATE SET watermark = excluded.watermark
                    WHERE payment_gateway.watermark IS NULL
                """)
        int writeIfAbsent(@Bind("watermark") OffsetDateTime watermark);

        @SqlQuery("SELECT watermark FROM payment_gateway WHERE watermark IS NOT NULL")
        Optional<OffsetDateTime> watermark();
    }
}
