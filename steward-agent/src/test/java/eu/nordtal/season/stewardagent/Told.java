package eu.nordtal.season.stewardagent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.stewardagent.plan.PlanReport;
import eu.nordtal.season.stewardagent.plan.UpdatePlan;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Renders what a run told in English, for the tests that read report notes and lines. */
public final class Told {

    /** The shape of the report with every message in English, for the tests that read it. */
    private static final Gson ENGLISH = Json.gson()
            .newBuilder()
            .registerTypeHierarchyAdapter(MessageRef.class, (JsonSerializer<MessageRef>)
                    (message, type, context) -> new JsonPrimitive(DatabaseText.english(message)))
            .registerTypeAdapter(UpdateReport.ServiceLine.class, (JsonSerializer<UpdateReport.ServiceLine>)
                    (line, type, context) -> {
                        final JsonObject shown = new JsonObject();
                        shown.addProperty("service", line.service());
                        shown.add("state", context.serialize(line.state()));
                        shown.add("changes", context.serialize(line.changes()));
                        final MessageRef said = line.said();
                        if (said != null) {
                            shown.add("said", context.serialize(said, MessageRef.class));
                        }
                        if (line.facts() != null) {
                            shown.add("facts", context.serialize(line.facts()));
                        }
                        return shown;
                    })
            .create();

    private Told() {}

    public static @Nullable String english(final @Nullable MessageRef message) {
        return message == null ? null : DatabaseText.english(message);
    }

    public static List<String> english(final List<MessageRef> messages) {
        return messages.stream().map(DatabaseText::english).toList();
    }

    /** Returns one note as step, outcome, service ({@code -} for the run) and English text. */
    public static String record(final UpdateReport.Note note) {
        return note.step() + " " + note.outcome() + " " + (note.service() == null ? "-" : note.service()) + ": "
                + DatabaseText.english(note.what());
    }

    public static List<String> records(final List<UpdateReport.Note> notes) {
        return notes.stream().map(Told::record).toList();
    }

    public static String joined(final List<UpdateReport.Note> notes) {
        return String.join(". ", records(notes));
    }

    /** A stored report with its messages in English, and other text unchanged. */
    public static String report(final String stored) {
        return UpdateReports.parse(stored).map(ENGLISH::toJson).orElse(stored);
    }

    /** Returns a plan as the report a run would write, for a failed assertion. */
    public static String plan(final UpdatePlan plan) {
        return ENGLISH.toJson(PlanReport.of(plan));
    }

    public static List<String> notes(final UpdateReport report) {
        return records(report.notes());
    }

    /** Every change of a stored report that is not a message, which only an installed version may be. */
    public static List<UpdateReport.Change> untold(final String stored) {
        return untold(UpdateReports.parse(stored).orElseThrow());
    }

    public static List<UpdateReport.Change> untold(final UpdateReport report) {
        return report.services().stream()
                .flatMap(line -> line.changes().stream())
                .filter(change -> change.told() == null)
                .toList();
    }

    public static List<String> toldKeys(final String stored, final String line) {
        return UpdateReports.parse(stored).orElseThrow().line(line).changes().stream()
                .map(change -> change.told() == null ? "-" : change.told().key())
                .toList();
    }

    public static String said(final UpdateReport.ServiceLine line) {
        final MessageRef said = line.said();
        return said == null ? "" : DatabaseText.english(said);
    }
}
