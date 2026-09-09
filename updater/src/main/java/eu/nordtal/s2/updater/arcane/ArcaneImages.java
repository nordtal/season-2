package eu.nordtal.s2.updater.arcane;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Arcane's project-updates payload, reduced to the one question this updater asks of it: which
 * compose service is running an image the registry has moved past.
 *
 * <h2>It arrives keyed by image, and is needed keyed by service</h2>
 * Arcane answers with two halves that have to be joined here - {@code services[]}, each carrying a
 * {@code name} and the {@code image} its compose config resolves to, and {@code updateInfo}, whose
 * results are keyed by <b>image reference</b>. Nothing upstream joins them, and the join is not a
 * formality: all four Minecraft services share one image, so one entry in
 * {@code updatedImageRefs} is four containers to recreate. Doing it the other way round - one
 * result per service - would have made a shared image four independent facts that can disagree.
 *
 * <h2>Read raw, and named field by field</h2>
 * Same argument as {@link ArcaneRuntime}: the payload is large, mostly irrelevant here and belongs
 * to somebody else's release cycle. The names below were read from Arcane's own source on
 * 2026-09-09, v2.10.2 - {@code types/project/project.go} for {@code updateInfo}
 * ({@code hasUpdate}, {@code updatedImageRefs}, {@code updateInfoByRef}) and
 * {@code backend/internal/project/project_details.go} for the service list, which the
 * {@code /updates} endpoint fills because it asks for {@code IncludeServiceConfigs}.
 *
 * <h2>An image nobody has checked is not an image that is current</h2>
 * {@code updateInfo} is built from Arcane's <em>persisted</em> check results. A reference missing
 * from {@code updateInfoByRef} means nobody has looked, and it becomes
 * {@link ImageResult.State#UNKNOWN} rather than {@code UP_TO_DATE}. {@link ImageResult} carries why
 * that distinction is the whole point.
 */
final class ArcaneImages {

    private ArcaneImages() {
    }

    static @NotNull Map<String, ImageResult.State> parse(final String body) {
        final JsonElement root = JsonParser.parseString(body == null ? "" : body);
        if (!root.isJsonObject()) {
            return Map.of();
        }
        final JsonObject object = unwrap(root.getAsJsonObject());
        final JsonObject updateInfo = object(object, "updateInfo", "update_info");
        final Set<String> outdated = outdatedRefs(updateInfo);
        final Set<String> checked = checkedRefs(updateInfo, outdated);

        final Map<String, ImageResult.State> services = new LinkedHashMap<>();
        for (final JsonElement element : array(object, "services")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject service = element.getAsJsonObject();
            final String name = text(service, "name", "service", "serviceName");
            if (name == null) {
                continue;
            }
            final String image = text(service, "image");
            // A service with no image of its own - one that is built rather than pulled, or one
            // whose config Arcane could not resolve - is UNKNOWN and never work. Recreating a
            // container on a guess is the one outcome this whole path exists to avoid.
            services.put(name, image == null ? ImageResult.State.UNKNOWN : state(image, outdated, checked));
        }
        return Map.copyOf(services);
    }

    private static ImageResult.State state(final String image, final Set<String> outdated,
                                           final Set<String> checked) {
        if (outdated.contains(image)) {
            return ImageResult.State.OUTDATED;
        }
        return checked.contains(image) ? ImageResult.State.UP_TO_DATE : ImageResult.State.UNKNOWN;
    }

    /** The references Arcane says have a newer image behind them. */
    private static Set<String> outdatedRefs(final JsonObject updateInfo) {
        final Set<String> refs = new LinkedHashSet<>();
        if (updateInfo == null) {
            return refs;
        }
        for (final JsonElement element : array(updateInfo, "updatedImageRefs", "updated_image_refs")) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                refs.add(element.getAsString());
            }
        }
        // The per-reference map is the more detailed of the two and the two can disagree only by
        // one being absent, so both are read and the union taken: a summary list that stops being
        // filled in a later Arcane must not turn "outdated" into "up to date" in silence.
        final JsonObject byRef = object(updateInfo, "updateInfoByRef", "update_info_by_ref");
        if (byRef != null) {
            for (final Map.Entry<String, JsonElement> entry : byRef.entrySet()) {
                if (entry.getValue().isJsonObject()
                        && bool(entry.getValue().getAsJsonObject(), "hasUpdate", "has_update")) {
                    refs.add(entry.getKey());
                }
            }
        }
        return refs;
    }

    /** Every reference Arcane has a result for at all, outdated or not. */
    private static Set<String> checkedRefs(final JsonObject updateInfo, final Set<String> outdated) {
        final Set<String> refs = new LinkedHashSet<>(outdated);
        final JsonObject byRef = updateInfo == null
                ? null : object(updateInfo, "updateInfoByRef", "update_info_by_ref");
        if (byRef != null) {
            refs.addAll(byRef.keySet());
        }
        return refs;
    }

    /**
     * Arcane wraps its Huma responses, and which key the body arrives under has moved between
     * versions. The same generosity {@link ArcaneRuntime} applies, for the same reason: being
     * strict here costs an image that is never renewed, and being generous costs nothing, because
     * a payload that yields no services is {@code UNKNOWN} throughout and is never work.
     */
    private static JsonObject unwrap(final JsonObject root) {
        for (final String key : new String[] {"data", "body", "project"}) {
            final JsonElement wrapped = root.get(key);
            if (wrapped != null && wrapped.isJsonObject() && root.get("services") == null) {
                return wrapped.getAsJsonObject();
            }
        }
        return root;
    }

    private static JsonArray array(final JsonObject object, final String... names) {
        for (final String name : names) {
            final JsonElement value = object.get(name);
            if (value != null && value.isJsonArray()) {
                return value.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    private static JsonObject object(final JsonObject object, final String... names) {
        if (object == null) {
            return null;
        }
        for (final String name : names) {
            final JsonElement value = object.get(name);
            if (value != null && value.isJsonObject()) {
                return value.getAsJsonObject();
            }
        }
        return null;
    }

    private static boolean bool(final JsonObject object, final String... names) {
        for (final String name : names) {
            final JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
        }
        return false;
    }

    private static String text(final JsonObject object, final String... names) {
        for (final String name : names) {
            final JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                final String text = value.getAsString();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }
}
