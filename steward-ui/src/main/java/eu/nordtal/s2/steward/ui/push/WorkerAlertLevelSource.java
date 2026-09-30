package eu.nordtal.s2.steward.ui.push;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@link AlertLevelSource} over {@code GET /api/alert-level} on steward-worker.
 *
 * An absent number stays null, since a disk nobody could read must not read as empty.
 */
final class WorkerAlertLevelSource implements AlertLevelSource {

    private final InternalClient worker;

    WorkerAlertLevelSource(final InternalClient worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Override
    public AlertReading current() {
        final JsonObject body = Json.decode(worker.get("/api/alert-level"), JsonObject.class);
        if (body == null) {
            return new AlertReading(List.of(), null, null, null);
        }
        final List<AlertReading.Trigger> triggers = new ArrayList<>();
        final JsonElement listed = body.get("triggers");
        if (listed != null && listed.isJsonArray()) {
            final JsonArray rows = listed.getAsJsonArray();
            for (final JsonElement row : rows) {
                if (!row.isJsonObject()) {
                    continue;
                }
                final JsonObject trigger = row.getAsJsonObject();
                triggers.add(new AlertReading.Trigger(
                        text(trigger, "kind"),
                        text(trigger, "level"),
                        text(trigger, "subject"),
                        text(trigger, "path")));
            }
        }
        return new AlertReading(
                triggers, number(body, "diskPercent"), number(body, "memoryPercent"), number(body, "backupAgeHours"));
    }

    private static String text(final JsonObject body, final String field) {
        return body.has(field) && !body.get(field).isJsonNull()
                ? body.get(field).getAsString()
                : "";
    }

    private static @Nullable Double number(final JsonObject body, final String field) {
        if (!body.has(field) || body.get(field).isJsonNull()) {
            return null;
        }
        try {
            return body.get(field).getAsDouble();
        } catch (final NumberFormatException | UnsupportedOperationException notANumber) {
            return null;
        }
    }
}
