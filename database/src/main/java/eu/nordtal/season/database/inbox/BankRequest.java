package eu.nordtal.season.database.inbox;

import eu.nordtal.season.database.notify.Channel;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A request to the process that holds the bank key, about one payment request. Each record is one kind. */
public sealed interface BankRequest {

    /** The bank's inbox table, announced on the payment channel. */
    InboxTable<BankRequest> TABLE =
            InboxTable.of("bank_inbox", Channel.PAYMENT, BankRequest.class).withRows(OpenTab.class, CancelTab.class);

    UUID payment();

    /** Makes the bunq.me tab for an open request without one. Asking again after a refusal is the retry. */
    record OpenTab(UUID payment) implements BankRequest {

        public OpenTab {
            Objects.requireNonNull(payment, "payment");
        }

        /** @param tab the bunq.me tab it made, null if the request was gone, closed or had its tab */
        public record Answer(@Nullable Long tab) {}
    }

    /** Cancels the tab of a request at bunq. A request without a tab needs nothing. */
    record CancelTab(UUID payment) implements BankRequest {

        public CancelTab {
            Objects.requireNonNull(payment, "payment");
        }

        /** @param tab the bunq.me tab it cancelled, null if there was none standing */
        public record Answer(@Nullable Long tab) {}
    }
}
