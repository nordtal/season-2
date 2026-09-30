package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Refusal;
import java.util.Objects;

/** What an admin action answers, the same whether the console typed it or another process asked for it. */
public sealed interface Answer {

    /** Carried out. */
    record Done(MessageRef message) implements Answer {

        public Done {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Refused, which is an answer and not a fault. */
    record Refused(Refusal refusal) implements Answer {

        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /** Failed while it was carried out; the log says why. */
    record Failed(MessageRef message) implements Answer {

        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Returns a done answer. */
    static Answer done(final MessageRef message) {
        return new Done(message);
    }

    /** Returns a refused answer. */
    static Answer refused(final Refusal refusal) {
        return new Refused(refusal);
    }

    /** Returns a failed answer. */
    static Answer failed(final MessageRef message) {
        return new Failed(message);
    }
}
