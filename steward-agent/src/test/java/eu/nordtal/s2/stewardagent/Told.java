package eu.nordtal.s2.stewardagent;

import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.messages.MessageRef;
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

    /** Several notes as one text, the way a person reads them one after the other. */
    public static String joined(final List<MessageRef> messages) {
        return String.join(". ", english(messages));
    }

    /** A stored report with its messages in English, as the host's terminal prints it. */
    public static String report(final String stored) {
        return UpdateReports.english(stored);
    }

    public static List<String> notes(final UpdateReport report) {
        return english(report.notes());
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
