package eu.nordtal.s2.steward.ui.push;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link AlertLevelSource} over the wire: {@code GET /api/alert-level} on steward-worker.
 *
 * <p>Parsed by hand rather than into a shared DTO, for the reason {@link AlertReading}'s own class
 * note gives: the shape belongs to {@code AlertLevel} in {@code :steward-worker}, and this is the
 * one place in {@code :steward-ui} that reads it.</p>
 *
 * <p><b>An absent number stays absent.</b> {@code diskPercent}, {@code memoryPercent} and
 * {@code backupAgeHours} are left out of the body exactly when the worker could not measure them,
 * and they arrive here as null rather than as zero - a disk nobody could read must not become a
 * disk that is empty. {@link Alerts} skips a null and says nothing about it.</p>
 */
final class WorkerAlertLevelSource implements AlertLevelSource {

    private static final Gson GSON = new Gson();

    private final InternalClient worker;

    WorkerAlertLevelSource(final @NotNull InternalClient worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Override
    public @NotNull AlertReading current() {
        final JsonObject body = GSON.fromJson(worker.get("/api/alert-level"), JsonObject.class);
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
        return new AlertReading(triggers,
                number(body, "diskPercent"),
                number(body, "memoryPercent"),
                number(body, "backupAgeHours"));
    }

    private static String text(final JsonObject body, final String field) {
        return body.has(field) && !body.get(field).isJsonNull() ? body.get(field).getAsString() : "";
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
