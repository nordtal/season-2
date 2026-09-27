package eu.nordtal.s2.steward.worker;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.payment.PaymentGateway;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.common.payment.Watermark;
import eu.nordtal.s2.steward.worker.bunq.BunqGateway;
import eu.nordtal.s2.steward.worker.bunq.PaymentLoop;
import eu.nordtal.s2.steward.worker.bunq.Payments;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Whether this deployment can take money, and the loop that polls bunq when it can. */
@Slf4j
final class PaymentsStartup {

    private PaymentsStartup() {}

    /**
     * Says whether this deployment can take money, records it for the bot, and starts the loop if it can.
     *
     * @return the running loop, or {@code null} when there is no bunq to poll
     */
    static @Nullable PaymentLoop start(
            final StewardSpec config, final DatabaseSpec databaseConfig, final Database database) {
        final BunqGateway bunq = new BunqGateway(config.bunq());
        final Duration poll = Duration.ofSeconds(config.bunq().pollIntervalSeconds());

        try {
            PaymentGateway.announce(database.jdbi(), bunq.configured());
        } catch (final RuntimeException failure) {
            // One row in bot_setting is no reason to refuse to serve four servers.
            log.warn(
                    "Could not record whether bunq is configured; the bot will say it does not"
                            + " know rather than saying it is off.",
                    failure);
        }

        // Logged on both branches, so a renamed key cannot turn payments off silently.
        bunq.logStartupLine(poll);
        if (!bunq.configured()) {
            return null;
        }

        // The cut-off, resolved once and read back by every later start.
        final Instant watermark =
                Watermark.resolve(database.jdbi(), config.bunq().watermark());

        return PaymentLoop.start(
                new Payments(
                        bunq,
                        new PaymentRequests(database.jdbi()),
                        watermark,
                        config.bunq().recentPaymentCount()),
                eu.nordtal.s2.common.notify.PostgresNotifications.connector(
                        databaseConfig.jdbcUrl(),
                        databaseConfig.username(),
                        databaseConfig.password(),
                        databaseConfig.queryTimeoutSeconds(),
                        "steward-worker-payment-listener",
                        java.util.List.of(PaymentLoop.channel())),
                poll);
    }
}
