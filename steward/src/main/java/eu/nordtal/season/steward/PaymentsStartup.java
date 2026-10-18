package eu.nordtal.season.steward;

import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.inbox.BankRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.payment.Bookings;
import eu.nordtal.season.database.payment.PaymentGateway;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.database.payment.Watermark;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.steward.bunq.Bank;
import eu.nordtal.season.steward.bunq.PaymentLoop;
import eu.nordtal.season.steward.bunq.Payments;
import eu.nordtal.season.steward.config.StewardSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Asks steward-bunq for the account and runs the payment loop when there is one. */
@Slf4j
final class PaymentsStartup {

    /** How long a start waits for steward-bunq. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private PaymentsStartup() {}

    /**
     * Asks steward-bunq whether there is an account, records the answer for the bot and starts the loop.
     *
     * @param tiers the price list that books a matched payment
     * @return the running loop, or {@code null} when there is no bank to poll
     */
    static @Nullable PaymentLoop start(
            final StewardSpec config,
            final Tiers tiers,
            final Database database,
            final Waiting waiting,
            final Duration timeout,
            final Scheduler scheduler) {
        final StewardSpec.BunqSpec settings = config.bunq();
        final Duration poll = Duration.ofSeconds(settings.pollIntervalSeconds());
        if (settings.token().isBlank()) {
            announce(database, false);
            log.warn("Payments are OFF: bunq.token is empty (NORDTAL_STEWARD_BUNQ_TOKEN), so steward never asks"
                    + " steward-bunq anything and no payment will ever be noticed.");
            return null;
        }

        final Bank bank = new Bank(new InternalClient(BankWire.SERVICE, settings.url(), settings.token(), timeout));
        final Optional<BankWire.Account> account = waiting.until(
                () -> {
                    try {
                        return Optional.of(bank.account());
                    } catch (final InternalClient.Failure notYet) {
                        return Optional.empty();
                    }
                },
                PATIENCE,
                new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(8)));
        if (account.isEmpty()) {
            // No row is written, so the row keeps the result of the last start.
            log.error(
                    "Payments are OFF until steward restarts: steward-bunq did not answer at {} within {}s.",
                    settings.url(),
                    PATIENCE.toSeconds());
            return null;
        }

        announce(database, account.get().configured());
        if (!account.get().configured()) {
            log.warn("Payments are OFF: steward-bunq holds no bank key; its own start line says which variables.");
            return null;
        }
        log.info(
                "Payments are ON: monetary account {} through steward-bunq, polled every {}s.",
                account.get().id(),
                poll.toSeconds());

        final Instant watermark = Watermark.resolve(database.jdbi(), settings.watermark(), waiting.now());

        return PaymentLoop.start(
                new Payments(
                        bank,
                        new PaymentRequests(database.dataSource()),
                        new Bookings(database.dataSource()),
                        tiers,
                        AlertBook.using(database.dataSource()),
                        Inbox.over(database.dataSource(), BankRequest.TABLE),
                        watermark,
                        settings.recentPaymentCount()),
                poll,
                scheduler);
    }

    private static void announce(final Database database, final boolean configured) {
        try {
            PaymentGateway.announce(database.jdbi(), configured);
        } catch (final RuntimeException failure) {
            log.warn(
                    "Could not record whether bunq is configured; the bot will say it does not"
                            + " know rather than saying it is off.",
                    failure);
        }
    }
}
