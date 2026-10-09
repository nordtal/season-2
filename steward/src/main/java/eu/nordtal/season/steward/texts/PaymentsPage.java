package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.time.Instant;

/** The payments page's own words. */
@Name("Payments page")
public interface PaymentsPage {

    @Name("Settle")
    MessageRef settle();

    @Name("Settle, asked")
    MessageRef settleAsk();

    @Name("Settle, said")
    MessageRef settleNote(@Arg("reference") String reference);

    @Name("Settled")
    MessageRef settled(@Arg("reference") String reference);

    @Name("Settled, said")
    MessageRef settledNote(@Arg("days") int days, @Arg("until") Instant until);

    @Name("Not open")
    MessageRef notOpen(@Arg("reference") String reference);

    @Name("Not open, said")
    MessageRef notOpenNote(@Arg("was") String was);

    @Name("No such payment")
    MessageRef noPayment(@Arg("reference") String reference);

    @Name("Nothing booked")
    MessageRef nothingBooked();

    @Name("Nothing settled")
    MessageRef nothingSettled();

    @Name("A request's state")
    MessageRef state(@Arg("status") PaymentRequestStatus status);

    @Name("A request's state, said")
    MessageRef stateTip(@Arg("status") PaymentRequestStatus status);

    @Name("Title")
    MessageRef title();

    @Name("No request")
    MessageRef noRequest();

    @Name("No request, said")
    MessageRef noRequestNote();

    @Name("Open")
    MessageRef open();

    @Name("Past the deadline")
    MessageRef pastDeadline(@Arg("count") int count);

    @Name("Paid")
    MessageRef paid();

    @Name("Requested")
    MessageRef requested();

    @Name("Requested, said")
    MessageRef requestedHint();

    @Name("Requests")
    MessageRef requests();

    @Name("Status")
    MessageRef status();

    @Name("Every status")
    MessageRef all();

    @Name("Overdue, said")
    MessageRef overdueNote(@Arg("count") int count);

    @Name("None with this status")
    MessageRef noneWithStatus();

    @Name("None with this status, said")
    MessageRef noneWithStatusNote();

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
    MessageRef created(@Arg("at") Instant at);

    @Name("Donation")
    MessageRef donation();

    @Name("Overdue")
    MessageRef overdue();

    @Name("Overdue, said")
    MessageRef overdueTip();

    @Name("Tab")
    MessageRef tab();

    @Name("No tab")
    MessageRef noTab();
}
