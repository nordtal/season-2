package eu.nordtal.s2.steward.worker.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.steward.worker.configfile.MessageBundle;
import eu.nordtal.s2.steward.worker.configfile.MessageBundleLocation;
import eu.nordtal.s2.steward.worker.configfile.MessageBundles;
import eu.nordtal.s2.steward.worker.configfile.MessageEntry;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two routes over {@code eu.nordtal.s2.steward.worker.configfile.MessageBundles} (steward/48).
 *
 * <p>Deliberately its own class rather than three more methods on {@link ConfigApi}: a bundle is not
 * a config file - it has no YAML shape, no schema, and its own key-by-key override rule - and the
 * interface is asked to draw it as its own card, beside the configuration cards rather than inside
 * them. Keeping the two APIs apart is what makes that true on this side of the wire as well: nothing
 * a bundle needs changes what {@code GET /api/config} answers.</p>
 */
public final class MessagesApi {

    private static final Logger log = LoggerFactory.getLogger(MessagesApi.class);

    private final Path configsRoot;
    private final Path volumesRoot;

    public MessagesApi(final @NotNull Path configsRoot, final @Nullable Path volumesRoot) {
        this.configsRoot = configsRoot;
        this.volumesRoot = volumesRoot;
    }

    /** {@code GET /api/messages} - every bundle found, without opening a single jar. */
    public void list(final @NotNull Context ctx) {
        ctx.json(locations().stream().map(MessagesApi::describe).toList());
    }

    /** {@code GET /api/messages/<bundle>} - one bundle, packaged text and override side by side. */
    public void one(final @NotNull Context ctx) {
        final MessageBundleLocation location = locate(ctx);
        try {
            ctx.json(document(location, MessageBundles.read(location)));
        } catch (final IOException e) {
            log.error("{} could not be read", location.jar(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " could not be read: "
                    + e.getMessage());
        }
    }

    /**
     * {@code PUT /api/messages/<bundle>} - apply changes to one language's override file.
     *
     * <p>The body is {@code {"language": "en"|"de", "changes": {"key": "new text", "other": null}}}.
     * A {@code null} value resets that key - it is removed from the override rather than filled with
     * the packaged text, so the line goes back to following the jar (steward/48).</p>
     *
     * <p><b>A dropped placeholder is a warning, never a refusal</b> - the same rule steward/60 gives
     * a syntax error in the raw editor. The response always carries {@code warnings}, empty when
     * there was nothing to say.</p>
     */
    public void save(final @NotNull Context ctx) {
        final MessageBundleLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(identityOf(location) + " is mounted read-only in this"
                    + " container, so this interface cannot save a change to it.");
        }
        final JsonObject body = bodyOf(ctx.body());
        final String language = languageOf(body);
        final Map<String, String> changes = changesOf(body);

        final MessageBundle before;
        try {
            before = MessageBundles.read(location);
        } catch (final IOException e) {
            log.error("{} could not be read", location.jar(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " could not be read: "
                    + e.getMessage());
        }
        final List<String> warnings = warningsOf(before, language, changes);

        try {
            MessageBundles.write(location, language, changes);
        } catch (final IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        } catch (final IOException e) {
            log.error("{} could not be written", location.overrideDirectory(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " could not be written: "
                    + e.getMessage());
        }

        try {
            final Map<String, Object> answer = document(location, MessageBundles.read(location));
            answer.put("warnings", warnings);
            ctx.json(answer);
        } catch (final IOException e) {
            log.error("{} could not be read back after saving", location.jar(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " was saved but could not"
                    + " be read back: " + e.getMessage());
        }
    }

    /**
     * A dropped placeholder for every changed key that had one, checked against the packaged text -
     * the "original" the ticket means, not whatever the override said a moment ago.
     */
    private static List<String> warningsOf(final MessageBundle before, final String language,
                                           final Map<String, String> changes) {
        final List<String> warnings = new ArrayList<>();
        for (final Map.Entry<String, String> change : changes.entrySet()) {
            final String edited = change.getValue();
            if (edited == null) {
                // A reset. There is no new text to check placeholders against.
                continue;
            }
            final MessageEntry entry = before.entries().stream()
                    .filter(candidate -> candidate.key().equals(change.getKey()))
                    .findFirst().orElse(null);
            if (entry == null) {
                continue;
            }
            final String original = "de".equals(language) && entry.german() != null
                    ? entry.german() : entry.english();
            final List<String> missing = MessageBundles.missingPlaceholders(original, edited);
            if (!missing.isEmpty()) {
                warnings.add(change.getKey() + " no longer contains " + String.join(", ", missing)
                        + " - the original had it, and a message this is substituted into may now"
                        + " draw literally.");
            }
        }
        return warnings;
    }

    // ---------------------------------------------------------------------------------------
    // Finding the bundle
    // ---------------------------------------------------------------------------------------

    private List<MessageBundleLocation> locations() {
        return MessageBundles.discover(configsRoot, volumesRoot);
    }

    private MessageBundleLocation locate(final Context ctx) {
        final String asked = ctx.pathParam("bundle");
        return locations().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(
                        "There is no message bundle called " + asked + "."));
    }

    /** How a bundle is named in a URL: {@code <service>/<module>}, or just {@code <service>}. */
    private static String identityOf(final MessageBundleLocation location) {
        return location.module().isEmpty() ? location.service()
                : location.service() + "/" + location.module();
    }

    // ---------------------------------------------------------------------------------------
    // What goes over the wire
    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> describe(final MessageBundleLocation location) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", location.service());
        row.put("module", location.module());
        row.put("path", identityOf(location));
        row.put("writable", location.writable());
        return row;
    }

    private static Map<String, Object> document(final MessageBundleLocation location,
                                                final MessageBundle bundle) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        final List<Map<String, Object>> entries = new ArrayList<>(bundle.entries().size());
        for (final MessageEntry entry : bundle.entries()) {
            entries.add(describe(entry));
        }
        answer.put("entries", entries);
        return answer;
    }

    private static Map<String, Object> describe(final MessageEntry entry) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", entry.key());
        // Gson drops a null-valued map entry by default rather than writing `null` (unlike a field
        // on a POJO), and this class does not own the app-wide JsonMapper to turn serializeNulls()
        // on without changing what every other route answers. So absence IS the "no text in this
        // language" signal here, the same way ConfigApi already leaves `value`/`items` off a secret
        // entry rather than sending them as null.
        putIfPresent(row, "english", entry.english());
        putIfPresent(row, "german", entry.german());
        putIfPresent(row, "overrideEnglish", entry.overrideEnglish());
        putIfPresent(row, "overrideGerman", entry.overrideGerman());
        row.put("inBundle", entry.inBundle());
        return row;
    }

    private static void putIfPresent(final Map<String, Object> row, final String key, final String value) {
        if (value != null) {
            row.put(key, value);
        }
    }

    // ---------------------------------------------------------------------------------------
    // What comes in
    // ---------------------------------------------------------------------------------------

    private static JsonObject bodyOf(final String body) {
        try {
            return JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with `language` and"
                    + " `changes` fields.");
        }
    }

    private static String languageOf(final JsonObject body) {
        final JsonElement language = body.get("language");
        final String value = language == null || !language.isJsonPrimitive() ? "" : language.getAsString();
        if (!"en".equals(value) && !"de".equals(value)) {
            throw new BadRequestResponse("`language` has to be \"en\" or \"de\", not " + value);
        }
        return value;
    }

    private static Map<String, String> changesOf(final JsonObject body) {
        final JsonElement changes = body.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("`changes` has to be an object of key to new text (or"
                    + " null, to reset that key).");
        }
        final Map<String, String> answer = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> change : changes.getAsJsonObject().entrySet()) {
            final JsonElement value = change.getValue();
            answer.put(change.getKey(), value == null || value.isJsonNull() ? null : value.getAsString());
        }
        if (answer.isEmpty()) {
            throw new BadRequestResponse("`changes` is empty - there is nothing to save.");
        }
        return answer;
    }
}
