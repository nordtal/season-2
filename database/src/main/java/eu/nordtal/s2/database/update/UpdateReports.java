package eu.nordtal.s2.database.update;

import eu.nordtal.s2.common.json.Json;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link UpdateReport} to and from the JSON in {@code update_request.result}, through the kernel's codec.
 *
 * {@link #parse} answers empty for an unreadable row, and every caller falls back to the raw text.
 */
public final class UpdateReports {

    private UpdateReports() {}

    public static String toJson(final UpdateReport report) {
        return Json.encode(report);
    }

    /**
     * Parses the {@code result} column into a report.
     *
     * @param json the {@code result} column, which may be {@code null}, empty, or plain text
     * @return the report, or empty when this is not one
     */
    public static Optional<UpdateReport> parse(final @Nullable String json) {
        if (json == null || json.isBlank() || json.charAt(0) != '{') {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.decode(json, UpdateReport.class));
        } catch (final RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
