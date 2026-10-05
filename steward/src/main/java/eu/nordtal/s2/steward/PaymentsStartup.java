package eu.nordtal.s2.steward;

import eu.nordtal.s2.common.time.Backoff;
import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.inbox.BankRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.payment.Bookings;
import eu.nordtal.s2.database.payment.PaymentGateway;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.database.payment.Tiers;
import eu.nordtal.s2.database.payment.Watermark;
import eu.nordtal.s2.internalapi.BankWire;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.settings.Database;
import eu.nordtal.s2.steward.bunq.Bank;
import eu.nordtal.s2.steward.bunq.PaymentLoop;
import eu.nordtal.s2.steward.bunq.Payments;
import eu.nordtal.s2.steward.config.StewardSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Whether this deployment can take money, which steward-bunq answers, and the loop that polls it when it can. */
@Slf4j
final class PaymentsStartup {

    /** How long a start waits for steward-bunq, which compose starts before steward. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private PaymentsStartup() {}

    /**
     * Asks steward-bunq whether there is an account, records it for the bot, and starts the loop if there is.
     *
     * @param tiers the price list a matched payment is booked by
     *
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
            // Not announced: nobody knows, so the row keeps what the last start found and this line is the record.
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

        // The cut-off, resolved once and read back by every later start.
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
            // One row in payment_gateway is no reason to refuse to serve four servers.
            log.warn(
                    "Could not record whether bunq is configured; the bot will say it does not"
                            + " know rather than saying it is off.",
                    failure);
        }
    }
}
