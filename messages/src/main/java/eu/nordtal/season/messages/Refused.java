package eu.nordtal.season.messages;

/** Throws a {@link Refusal} out of a method that returns something else when it is not refused. */
public final class Refused extends RuntimeException {

    private final Refusal refusal;

    public Refused(final RefusalReason reason, final MessageRef message) {
        // No stack trace: a refusal is an answer, not a fault.
        super(reason.name() + ": " + message.key(), null, false, false);
        this.refusal = new Refusal(reason, message);
    }

    public Refusal refusal() {
        return refusal;
    }

    public RefusalReason reason() {
        return refusal.reason();
    }
}
