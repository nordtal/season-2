package eu.nordtal.s2.updater.arcane;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Arcane's runtime payload, reduced to the four fields this updater acts on.
 *
 * <h2>Read raw, and named field by field</h2>
 * The same argument {@code Json} in the {@code source} package makes about the three release APIs:
 * this payload is large, mostly irrelevant here, and belongs to somebody else's release cycle.
 * Naming the four fields we read means an upstream addition is ignored and an upstream
 * <em>removal</em> leaves an obviously empty value rather than a mapped object with a silent null
 * in it.
 *
 * <h2>The shape is not pinned down, so several are accepted</h2>
 * Arcane wraps its Huma responses, and which key the list arrives under has moved between versions.
 * Rather than pin one and have an update fail on a patch release with "no services", the array is
 * looked for under the handful of names it has used and then anywhere one object deep. A run that
 * finds no services stops before touching anything either way - {@link RuntimeResult} carries the
 * distinction - so being generous here costs nothing and being strict costs an outage.
 */
final class ArcaneRuntime {

    private ArcaneRuntime() {
    }

    /** The keys Arcane has carried the service list under. */
    private static final List<String> LIST_KEYS =
            List.of("services", "runtimeServices", "runtime_services", "data", "body");

    static @NotNull List<ServiceRuntime> parse(final String body) {
        final JsonElement root = JsonParser.parseString(body == null ? "" : body);
        final JsonArray array = findArray(root, 0);
        if (array == null) {
            return List.of();
        }
        final List<ServiceRuntime> services = new ArrayList<>(array.size());
        for (final JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject entry = element.getAsJsonObject();
            final String name = text(entry, "name", "service", "serviceName");
            if (name == null) {
                continue;
            }
            services.add(new ServiceRuntime(name,
                    text(entry, "containerId", "container_id", "id"),
                    text(entry, "status", "state"),
                    text(entry, "health", "healthStatus")));
        }
        return List.copyOf(services);
    }

    /** The first array that looks like a service list, at most two objects deep. */
    private static JsonArray findArray(final JsonElement element, final int depth) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray();
        }
        if (!element.isJsonObject() || depth > 2) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        for (final String key : LIST_KEYS) {
            final JsonArray found = findArray(object.get(key), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The first of those names that is present and a string. */
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
