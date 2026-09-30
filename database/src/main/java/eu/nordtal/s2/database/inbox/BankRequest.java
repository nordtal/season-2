package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.notify.Channel;
import java.util.Objects;
import java.util.UUID;

/** What the process holding the bank key is asked to do about one payment request; each record is one kind. */
public sealed interface BankRequest {

    /** The bank's inbox table, announced where every other change of a payment is. */
    InboxTable<BankRequest> TABLE = InboxTable.of("bank_inbox", Channel.PAYMENT, BankRequest.class);

    /** Returns the payment request this is about. */
    UUID payment();

    /** Makes the bunq.me tab for an open request that has none; asking again after a refusal is the retry. */
    record OpenTab(UUID payment) implements BankRequest {

        public OpenTab {
            Objects.requireNonNull(payment, "payment");
        }
    }

    /** Takes a request's tab away at bunq, whatever the request's status; one without a tab needs nothing. */
    record CancelTab(UUID payment) implements BankRequest {

        public CancelTab {
            Objects.requireNonNull(payment, "payment");
        }
    }
}
