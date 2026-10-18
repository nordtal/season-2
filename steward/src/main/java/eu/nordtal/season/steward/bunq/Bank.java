package eu.nordtal.season.steward.bunq;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.InternalClient;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/** Calls steward-bunq on the internal network, which alone holds the key and the bunq SDK. Every amount is in whole euro cents. */
@Slf4j
public final class Bank {

    private static final TypeToken<List<BankWire.Payment>> PAYMENTS = new TypeToken<>() {};

    private final InternalClient client;

    public Bank(final InternalClient client) {
        this.client = client;
    }

    public BankWire.Account account() {
        return Json.decode(client.get(BankWire.ACCOUNT), BankWire.Account.class);
    }

    /** @throws TabRefused with the reason and the words that steward-bunq answered */
    public BankWire.Tab createTab(final int amountCents, final String description) {
        try {
            return Json.decode(
                    client.post(BankWire.TABS, Json.encode(new BankWire.NewTab(amountCents, description))),
                    BankWire.Tab.class);
        } catch (final InternalClient.Failure refused) {
            throw TabRefused.of(refused);
        }
    }

    /** Signals that bunq or steward-bunq made no tab. {@link #refusal} holds the reason. */
    public static final class TabRefused extends RuntimeException {

        private final TabRefusal refusal;

        TabRefused(final TabRefusal refusal, final @Nullable String message, final Throwable cause) {
            super(message, cause);
            this.refusal = refusal;
        }

        /** Reads the answer of steward-bunq. No body means it did not answer. A 503 with a body means its key is missing. */
        static TabRefused of(final InternalClient.Failure failure) {
            final BankWire.Refused said = refusedIn(failure.body());
            if (said == null) {
                return new TabRefused(
                        failure.body() == null ? TabRefusal.UNREACHABLE : TabRefusal.UNKNOWN,
                        failure.getMessage(),
                        failure);
            }
            final TabRefusal refusal;
            if (failure.status() == 503) {
                refusal = TabRefusal.NOT_CONFIGURED;
            } else if (said.bunqStatus() == null) {
                refusal = TabRefusal.UNKNOWN;
            } else {
                refusal = TabRefusal.ofBunqStatus(said.bunqStatus());
            }
            return new TabRefused(refusal, said.message(), failure);
        }

        private static BankWire.@Nullable Refused refusedIn(final @Nullable String body) {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                final BankWire.Refused said = Json.decode(body, BankWire.Refused.class);
                // Gson fills a field the body lacks with null, whatever the record says.
                return said == null || said.message() == null ? null : said;
            } catch (final RuntimeException notOurs) {
                return null;
            }
        }

        public TabRefusal refusal() {
            return refusal;
        }
    }

    /** Returns false when the bank did not accept the cancel or could not be asked. */
    public boolean cancelTab(final long tabId) {
        try {
            return Json.decode(client.post(BankWire.of(BankWire.CANCEL, tabId), "{}"), BankWire.Cancelled.class)
                    .accepted();
        } catch (final InternalClient.Failure failure) {
            log.warn("Could not cancel bunq.me tab {}: {}", tabId, failure.getMessage());
            return false;
        }
    }

    public List<BankWire.Payment> paymentsFor(final long tabId) {
        return Json.decode(client.get(BankWire.of(BankWire.TAB_PAYMENTS, tabId)), PAYMENTS);
    }

    /** Returns the incoming payments among the account's latest {@code count}, newest first. */
    public List<BankWire.Payment> recentPayments(final int count) {
        return Json.decode(client.get(BankWire.RECENT + "?count=" + count), PAYMENTS);
    }
}
