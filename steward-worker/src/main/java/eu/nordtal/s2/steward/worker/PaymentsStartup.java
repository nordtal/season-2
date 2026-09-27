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

/** Whether this deployment can take money, and the loop that polls bunq when it can - split out of {@code serve}. */
@Slf4j
final class PaymentsStartup {

    private PaymentsStartup() {}

    /**
     * Says out loud whether this deployment can take money, records it for the bot, and starts the loop if it can.
     *
     * The line is not conditional and the loop is: An empty pair of credentials is a valid configuration - a season
     * without a bank account is a season where everything works except buying access - so there is nothing here to
     * refuse and nothing to fail. What there is, is a way for a rename in one file and not the other to turn payments
     * off without a single thing going red. That is what the line is for, and it is why it is written on the "off"
     * branch too, at WARN.
     *
     * {@code PaymentGateway.announce} carries the same answer into {@code bot_setting}, because the process that
     * offers the purchase button is {@code discord-bot} and it has no bunq configuration of its own any more. The bot
     * waits for this container's health marker before it starts, so the value it reads is this deployment's and not
     * the previous boot's.
     *
     * @return the running loop, or {@code null} when there is no bunq to poll - which try-with-resources handles by
     *     not closing anything
     */
    static @Nullable PaymentLoop start(
            final StewardSpec config, final DatabaseSpec databaseConfig, final Database database) {
        final BunqGateway bunq = new BunqGateway(config.bunq());
        final Duration poll = Duration.ofSeconds(config.bunq().pollIntervalSeconds());

        try {
            PaymentGateway.announce(database.jdbi(), bunq.configured());
        } catch (final RuntimeException failure) {
            // One row in bot_setting, for one log line elsewhere - not a reason to refuse to serve four servers.
            log.warn(
                    "Could not record whether bunq is configured; the bot will say it does not"
                            + " know rather than saying it is off.",
                    failure);
        }

        // Written on both branches: BunqGateway owns the level as well as the words, so both are provable by a test.
        bunq.logStartupLine(poll);
        if (!bunq.configured()) {
            return null;
        }

        // The cut-off, resolved once and read back by every later start - the row access.yml's watermark used.
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
