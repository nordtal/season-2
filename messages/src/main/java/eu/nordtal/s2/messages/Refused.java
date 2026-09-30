package eu.nordtal.s2.messages;

/** Carries a {@link Refusal} out of a method whose answer, when it is not refused, is something else. */
public final class Refused extends RuntimeException {

    private final Refusal refusal;

    public Refused(final RefusalReason reason, final MessageRef message) {
        // No stack trace: a refusal is an answer, not a fault, and nothing reads where it was thrown.
        super(reason.name() + ": " + message.key(), null, false, false);
        this.refusal = new Refusal(reason, message);
    }

    public Refusal refusal() {
        return refusal;
    }

    /** Returns {@code refusal().reason()}, which is what a catch usually branches on. */
    public RefusalReason reason() {
        return refusal.reason();
    }
}
