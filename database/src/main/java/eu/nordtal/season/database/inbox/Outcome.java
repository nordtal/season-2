package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.Refusal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** What a consumer answers a claimed request with. The inbox settles the row from it. */
public sealed interface Outcome {

    /** Carried out, with the answer of the kind or null. */
    record Done(@Nullable Object answer) implements Outcome {}

    /** Refused, which is an answer, not a fault. */
    record Refused(Refusal refusal) implements Outcome {

        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /** Failed while carried out. It is not retried. */
    record Failed(@Nullable Object answer) implements Outcome {}

    static Outcome done(final @Nullable Object answer) {
        return new Done(answer);
    }

    static Outcome refused(final Refusal refusal) {
        return new Refused(refusal);
    }

    static Outcome failed(final @Nullable Object answer) {
        return new Failed(answer);
    }
}
