package eu.nordtal.season.database.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.DatabaseJson;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.messages.MessageRef;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link UpdateReport} to and from the JSON in the run's outcome, through the codec that types its messages.
 *
 * {@link #parse} answers empty for an unreadable row, and every caller falls back to the raw text.
 */
public final class UpdateReports {

    /** The report's own shape with every message in English, and what each line says, for a terminal. */
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

    private UpdateReports() {}

    public static String toJson(final UpdateReport report) {
        return DatabaseJson.encode(report);
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
            return Optional.of(DatabaseJson.decode(json, UpdateReport.class));
        } catch (final RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /** The stored report as JSON with its messages in English; text that is no report comes back as it was. */
    public static String english(final String stored) {
        return parse(stored).map(ENGLISH::toJson).orElse(stored);
    }
}
