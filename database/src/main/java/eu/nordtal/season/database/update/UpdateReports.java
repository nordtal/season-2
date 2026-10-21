package eu.nordtal.season.database.update;

import eu.nordtal.season.database.DatabaseJson;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Converts {@link UpdateReport} to and from the JSON in the outcome of a run, with the codec of its messages.
 * {@link #parse} answers empty for an unreadable row, and every caller falls back to the raw text.
 */
public final class UpdateReports {

    private UpdateReports() {}

    public static String toJson(final UpdateReport report) {
        return DatabaseJson.encode(report);
    }

    /**
     * Parses the {@code result} column into a report.
     *
     * @param json the column, which may be null, empty or plain text
     * @return the report, or empty if the text is none
     */
    public static Optional<UpdateReport> parse(final @Nullable String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '{') {
            return Optional.empty();
        }
        try {
            return Optional.of(DatabaseJson.decode(json, UpdateReport.class));
        } catch (final RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
