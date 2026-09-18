package eu.nordtal.s2.steward.ui.push;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * {@link AlertLevelSource} over the wire: {@code GET /api/alert-level} on steward-worker.
 *
 * <p>Parsed by hand rather than into a shared DTO, for the reason {@link AlertReading}'s own class
 * note gives: the shape belongs to {@code AlertLevel} in {@code :steward-worker}, and this is the
 * one place in {@code :steward-ui} that reads three fields out of it.</p>
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
        return new AlertReading(
                text(body, "level"),
                text(body, "subject"),
                text(body, "path"));
    }

    private static String text(final JsonObject body, final String field) {
        return body.has(field) && !body.get(field).isJsonNull() ? body.get(field).getAsString() : "";
    }
}
