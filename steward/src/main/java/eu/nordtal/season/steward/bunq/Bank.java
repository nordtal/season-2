package eu.nordtal.season.steward.bunq;

import com.google.gson.reflect.TypeToken;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.InternalClient;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * The bank as steward sees it: steward-bunq on the internal network, which alone holds the key and the bunq SDK.
 *
 * Every answer is already whole cents in EUR; nothing here knows how bunq writes an amount or a date.
 */
@Slf4j
public final class Bank {

    private static final TypeToken<List<BankWire.Payment>> PAYMENTS = new TypeToken<>() {};

    private final InternalClient client;

    public Bank(final InternalClient client) {
        this.client = client;
    }

    /** Asks whether a bank account is behind steward-bunq, and which. */
    public BankWire.Account account() {
        return Json.decode(client.get(BankWire.ACCOUNT), BankWire.Account.class);
    }

    /**
     * Opens a bunq.me tab.
     *
     * @throws IllegalStateException carrying bunq's own words, which the person waiting is shown
     */
    public BankWire.Tab createTab(final int amountCents, final String description) {
        try {
            return Json.decode(
                    client.post(BankWire.TABS, Json.encode(new BankWire.NewTab(amountCents, description))),
                    BankWire.Tab.class);
        } catch (final InternalClient.Failure refused) {
            final String said = refused.body();
            throw new IllegalStateException(said == null || said.isBlank() ? refused.getMessage() : said, refused);
        }
    }

    /** Closes a tab so it can no longer be paid; {@code false} when the bank did not, or could not be asked. */
    public boolean cancelTab(final long tabId) {
        try {
            return Json.decode(client.post(BankWire.of(BankWire.CANCEL, tabId), "{}"), BankWire.Cancelled.class)
                    .accepted();
        } catch (final InternalClient.Failure failure) {
            log.warn("Could not cancel bunq.me tab {}: {}", tabId, failure.getMessage());
            return false;
        }
    }

    /** Returns the payments that settled one tab, possibly none. */
    public List<BankWire.Payment> paymentsFor(final long tabId) {
        return Json.decode(client.get(BankWire.of(BankWire.TAB_PAYMENTS, tabId)), PAYMENTS);
    }

    /** Returns the incoming payments among the account's latest {@code count}, newest first. */
    public List<BankWire.Payment> recentPayments(final int count) {
        return Json.decode(client.get(BankWire.RECENT + "?count=" + count), PAYMENTS);
    }
}
