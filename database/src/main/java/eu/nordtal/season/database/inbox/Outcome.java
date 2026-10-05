package eu.nordtal.season.database.inbox;

import eu.nordtal.season.messages.Refusal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** What a consumer answers a claimed request with; the inbox settles the row from it. */
public sealed interface Outcome {

    /** Carried out, with the kind's answer, or {@code null} for none. */
    record Done(@Nullable Object answer) implements Outcome {}

    /** Refused, which is an answer and not a fault. */
    record Refused(Refusal refusal) implements Outcome {

        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /** Failed while it was carried out; it is never retried. */
    record Failed(@Nullable Object answer) implements Outcome {}

    /** Returns a done outcome. */
    static Outcome done(final @Nullable Object answer) {
        return new Done(answer);
    }

    /** Returns a refused outcome. */
    static Outcome refused(final Refusal refusal) {
        return new Refused(refusal);
    }

    /** Returns a failed outcome. */
    static Outcome failed(final @Nullable Object answer) {
        return new Failed(answer);
    }
}
