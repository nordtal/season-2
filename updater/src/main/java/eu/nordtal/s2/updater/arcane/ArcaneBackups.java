package eu.nordtal.s2.updater.arcane;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Arcane's backup payloads, reduced to the two fields this updater acts on.
 *
 * <p>The same argument {@link ArcaneRuntime} makes, for the same reason: the payload is large,
 * mostly irrelevant here, and belongs to somebody else's release cycle. Two fields are named - the
 * id and the status - and everything else is ignored, so an upstream addition costs nothing and an
 * upstream removal leaves an obviously empty value instead of a silent null in a mapped object.</p>
 *
 * <h2>The wrapper key has moved before</h2>
 * A Huma response is {@code {"success":true,"data":...}} today; {@code ArcaneRuntime} already
 * carries the scar of that key changing between versions. So the object is looked for under the
 * handful of names it has used and then one level down, and a payload that yields nothing is
 * reported as an unreadable poll rather than as a failed backup - see {@link BackupResult}.
 */
final class ArcaneBackups {

    private ArcaneBackups() {
    }

    /** The keys Arcane has wrapped a single entry or a list under. */
    private static final List<String> WRAPPERS = List.of("data", "body", "backup", "result");

    /**
     * The body as JSON, or {@code JsonNull} when it is not JSON at all.
     *
     * <p>{@code JsonParser.parseString} throws {@code JsonSyntaxException}, which is unchecked.
     * Both callers sit behind {@code Arcane}, whose {@code catch} lists {@code IOException} and
     * {@code InterruptedException} - so a 2xx carrying a proxy's HTML error page would have thrown
     * straight out of {@code UpdateRun#save}, past the {@code run.start(...)} in
     * {@code Runner#backupUnderLock} that brings the network back. A malformed body would have left
     * the servers stopped.</p>
     *
     * <p>Answering {@code JsonNull} instead lets the two readers fall through their existing "this
     * payload carried none" branches, which the run already treats as "keep waiting" rather than as
     * a failure.</p>
     */
    private static JsonElement parse(final @Nullable String body) {
        if (body == null || body.isBlank()) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonParser.parseString(body);
        } catch (final RuntimeException malformed) {
            return JsonNull.INSTANCE;
        }
    }

    /**
     * The entry a {@code POST .../backups} answered with.
     *
     * @return the backup's id, or {@code null} when the payload carried none
     */
    static @Nullable String startedId(final String body) {
        final JsonObject entry = firstObject(parse(body), 0);
        return entry == null ? null : text(entry, "id", "backupId", "backup_id");
    }

    /**
     * The status of one backup out of a {@code GET .../backups} list.
     *
     * @param id the backup this run started; every other entry in the list belongs to an older run
     *           and saying anything about one of those would be answering about the wrong snapshot
     * @return {@code running}, {@code succeeded}, {@code failed} lowercased, or {@code null} when
     *         that id is not in the page at all
     */
    static @Nullable Entry find(final String body, final @NotNull String id) {
        final JsonArray array = firstArray(parse(body), 0);
        if (array == null) {
            return null;
        }
        for (final JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            if (!id.equals(text(object, "id", "backupId", "backup_id"))) {
                continue;
            }
            final String status = text(object, "status", "state");
            return new Entry(status == null ? "" : status.toLowerCase(Locale.ROOT),
                    text(object, "error", "message"));
        }
        return null;
    }

    /** One backup out of the list: what it is doing and, if it went wrong, why. */
    record Entry(@NotNull String status, @Nullable String error) {

        boolean succeeded() {
            return "succeeded".equals(status) || "success".equals(status)
                    || "completed".equals(status);
        }

        boolean failed() {
            return "failed".equals(status) || "error".equals(status);
        }
    }

    /** The first object that could be a backup entry, at most two wrappers deep. */
    private static JsonObject firstObject(final JsonElement element, final int depth) {
        if (element == null || element.isJsonNull() || depth > 2) {
            return null;
        }
        if (element.isJsonArray()) {
            final JsonArray array = element.getAsJsonArray();
            return array.isEmpty() || !array.get(0).isJsonObject()
                    ? null : array.get(0).getAsJsonObject();
        }
        if (!element.isJsonObject()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        // An object that already names an id IS the entry - checked before descending, so a
        // wrapper that happens to carry a "data" of its own cannot lead past it.
        if (text(object, "id", "backupId", "backup_id") != null) {
            return object;
        }
        for (final String key : WRAPPERS) {
            final JsonObject found = firstObject(object.get(key), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first array that could be the backup list, at most two wrappers deep. */
    private static JsonArray firstArray(final JsonElement element, final int depth) {
        if (element == null || element.isJsonNull() || depth > 2) {
            return null;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        if (!element.isJsonObject()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        for (final String key : WRAPPERS) {
            final JsonArray found = firstArray(object.get(key), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String text(final JsonObject object, final String... names) {
        for (final String name : names) {
            final JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive()) {
                final String text = value.getAsString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
