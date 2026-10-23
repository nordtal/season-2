package eu.nordtal.season.stewardagent;

import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import eu.nordtal.season.stewardagent.plan.Work;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Renders what a run told in English, for the tests that read its messages. */
public final class Told {

    private Told() {}

    public static @Nullable String english(final @Nullable MessageRef message) {
        return message == null ? null : DatabaseText.english(message);
    }

    public static List<String> english(final List<MessageRef> messages) {
        return messages.stream().map(DatabaseText::english).toList();
    }

    /** Returns a plan as the steps a run would write from it, for a failed assertion. */
    public static String plan(final UpdatePlan plan) {
        return Work.of(plan) + " from " + plan;
    }

    /** Returns the notes of the resolver in English. */
    public static List<String> notes(final UpdatePlan plan) {
        return english(plan.notes());
    }
}
