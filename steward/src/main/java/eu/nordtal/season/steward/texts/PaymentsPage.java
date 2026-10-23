package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.Instant;

/** The words of the payments page. */
@Name("Payments page")
public interface PaymentsPage {

    @Name("Settle")
    MessageRef settle();

    @Name("Settle, asked")
    MessageRef settleAsk();

    @Name("Settle, said")
    MessageRef settleNote(@Arg("reference") @Example("NT-A1B2C3") String reference);

    @Name("Settled")
    MessageRef settled(@Arg("reference") @Example("NT-A1B2C3") String reference);

    @Name("Settled, said")
    MessageRef settledNote(
            @Arg("days") @Example("30") int days, @Arg("until") @Example("2026-11-22T18:00:00Z") Instant until);

    @Name("Not open")
    MessageRef notOpen(@Arg("reference") @Example("NT-A1B2C3") String reference);

    @Name("Not open, said")
    MessageRef notOpenNote(@Arg("was") @Example("paid") String was);

    @Name("No such payment")
    MessageRef noPayment(@Arg("reference") @Example("NT-A1B2C3") String reference);

    @Name("Nothing booked")
    MessageRef nothingBooked();

    @Name("A request's state")
    MessageRef state(@Arg("status") PaymentRequestStatus status);

    @Name("Title")
    MessageRef title();

    @Name("No request")
    MessageRef noRequest();

    @Name("Open")
    MessageRef open();

    @Name("Past the deadline")
    MessageRef pastDeadline(@Arg("count") @Example("2") int count);

    @Name("Paid")
    MessageRef paid();

    @Name("Requested")
    MessageRef requested();

    @Name("Requests")
    MessageRef requests();

    @Name("Status")
    MessageRef status();

    @Name("No match")
    MessageRef noMatch();

    @Name("Filter")
    MessageRef filter();

    @Name("Reference")
    MessageRef reference();

    @Name("Person")
    MessageRef person();

    @Name("Days")
    MessageRef days();

    @Name("Amount")
    MessageRef amount();

    @Name("Deadline")
    MessageRef deadline();

    @Name("Created")
    MessageRef created(@Arg("at") @Example("2026-10-20T09:30:00Z") Instant at);

    @Name("Donation")
    MessageRef donation();

    @Name("Overdue")
    MessageRef overdue();

    @Name("Overdue only")
    MessageRef onlyOverdue();

    @Name("Tab")
    MessageRef tab();

    @Name("No tab")
    MessageRef noTab();
}
