package eu.nordtal.season.papercommon.command;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Refusal;
import java.util.Objects;

/** The answer to an admin action, from the console or another process. */
public sealed interface Answer {

    /** The action was carried out. */
    record Done(MessageRef message) implements Answer {

        public Done {
            Objects.requireNonNull(message, "message");
        }
    }

    /** The action was refused. */
    record Refused(Refusal refusal) implements Answer {

        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /** The action failed. The log says why. */
    record Failed(MessageRef message) implements Answer {

        public Failed {
            Objects.requireNonNull(message, "message");
        }
    }

    static Answer done(final MessageRef message) {
        return new Done(message);
    }

    static Answer refused(final Refusal refusal) {
        return new Refused(refusal);
    }

    static Answer failed(final MessageRef message) {
        return new Failed(message);
    }
}
