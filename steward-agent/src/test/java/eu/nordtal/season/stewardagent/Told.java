package eu.nordtal.season.stewardagent;

import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.messages.MessageRef;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What a run told, in English, the way a test reads a report's notes and a line's detail. */
public final class Told {

    private Told() {}

    public static @Nullable String english(final @Nullable MessageRef message) {
        return message == null ? null : DatabaseText.english(message);
    }

    public static List<String> english(final List<MessageRef> messages) {
        return messages.stream().map(DatabaseText::english).toList();
    }

    /** One note as its step, outcome, service ({@code -} for the run's own) and English text. */
    public static String record(final UpdateReport.Note note) {
        return note.step() + " " + note.outcome() + " " + (note.service() == null ? "-" : note.service()) + ": "
                + DatabaseText.english(note.what());
    }

    public static List<String> records(final List<UpdateReport.Note> notes) {
        return notes.stream().map(Told::record).toList();
    }

    /** Several notes as one text, the way a person reads them one after the other. */
    public static String joined(final List<UpdateReport.Note> notes) {
        return String.join(". ", records(notes));
    }

    /** A stored report with its messages in English, as the host's terminal prints it. */
    public static String report(final String stored) {
        return UpdateReports.english(stored);
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

    /** The keys of every change one line of a stored report tells, in order. */
    public static List<String> toldKeys(final String stored, final String line) {
        return UpdateReports.parse(stored).orElseThrow().line(line).changes().stream()
                .map(change -> change.told() == null ? "-" : change.told().key())
                .toList();
    }

    /** A line's detail, or the empty text when it has none. */
    public static String detail(final UpdateReport.ServiceLine line) {
        final MessageRef detail = line.detail();
        return detail == null ? "" : DatabaseText.english(detail);
    }
}
