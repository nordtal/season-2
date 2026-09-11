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
 * The answer has to be joined from two halves: a list of services, each with the {@code image} its
 * compose config resolves to, and {@code updateInfo}, whose results are keyed by <b>image
 * reference</b>. Nothing upstream joins them, and the join is not a formality: all four Minecraft
 * services share one image, so one entry in {@code updatedImageRefs} is four containers to
 * recreate. Doing it the other way round - one result per service - would have made a shared image
 * four independent facts that can disagree.
 *
 * <h2>The names come from the RUNTIME payload, because the updates payload has none</h2>
 * <b>This was wrong until 2026-09-11 and the whole feature was dead because of it.</b> The
 * {@code /updates} endpoint does fill a {@code services[]} array, but its entries are raw compose
 * <em>service configs</em> - {@code command}, {@code environment}, {@code healthcheck},
 * {@code image}, {@code volumes} - and they carry <b>no name field of any kind</b>. Measured
 * against the live Arcane v2.10.2 on 2026-09-11: eight entries, every one of them nameless. The
 * old code asked for {@code name} and skipped the entry when it was absent, so it skipped all
 * eight, every time, and handed back an empty map. That is indistinguishable from "Arcane has
 * never checked anything" - so every report carried a note blaming Arcane's configuration, while
 * Arcane was in fact answering {@code status: up_to_date} with all five images checked.
 *
 * <p>So the names are taken from {@code /runtime}'s {@code runtimeServices[]}, which carries
 * {@code name} and {@code image} together and is the same array {@link ArcaneRuntime} already
 * reads. The nameless {@code services[]} is still consulted as a fallback, purely so that a later
 * Arcane which starts putting names there keeps working without another round of this.</p>
 *
 * <h2>Read raw, and named field by field</h2>
 * Same argument as {@link ArcaneRuntime}: the payload is large, mostly irrelevant here and belongs
 * to somebody else's release cycle. The names below were read from Arcane's own source on
 * 2026-09-09, v2.10.2 - {@code types/project/project.go} for {@code updateInfo}
 * ({@code hasUpdate}, {@code updatedImageRefs}, {@code updateInfoByRef}) - and corrected against a
 * real response on 2026-09-11.
 *
 * <h2>An image nobody has checked is not an image that is current</h2>
 * {@code updateInfo} is built from Arcane's <em>persisted</em> check results. A reference missing
 * from {@code updateInfoByRef} means nobody has looked, and it becomes
 * {@link ImageResult.State#UNKNOWN} rather than {@code UP_TO_DATE}. {@link ImageResult} carries why
 * that distinction is the whole point.
 *
 * <p><b>{@code updateType: "local"} is one of those "nobody has looked" cases, and it is the one
 * that matters here.</b> Arcane classifies the image of any service carrying a {@code build:}
 * directive as local and never asks a registry about it: the entry comes back with an empty
 * {@code latestVersion} and {@code latestDigest} and {@code hasUpdate: false}. Reading that as
 * "up to date" would be a lie in the one direction this class must never lie in. Measured on
 * 2026-09-11 the correlation was exact - all seven services with a {@code build:} block were
 * {@code local}, and {@code postgres:17-alpine}, the only one without, was the only one really
 * checked. Our own four images all have such a block, deliberately, so this is not an edge case
 * here: it is every image we publish. {@link ImageResult#nothingChecked()} names the reason
 * instead of guessing at Arcane's settings.
 */
final class ArcaneImages {

    private ArcaneImages() {
    }

    /**
     * @param updatesBody what {@code /updates} answered - the check results
     * @param runtimeBody what {@code /runtime} answered, which is where the service names live.
     *                    May be {@code null} or unparseable; the result is then whatever the
     *                    updates body alone can yield, which today is nothing
     */
    static @NotNull ImageResult parse(final String updatesBody, final String runtimeBody) {
        final JsonElement root = JsonParser.parseString(updatesBody == null ? "" : updatesBody);
        if (!root.isJsonObject()) {
            return ImageResult.of(Map.of());
        }
        final JsonObject object = unwrap(root.getAsJsonObject());
        final JsonObject updateInfo = object(object, "updateInfo", "update_info");
        final Set<String> outdated = outdatedRefs(updateInfo);
        final Set<String> local = localRefs(updateInfo);
        // A reference Arcane holds only a `local` result for has not been checked against anything,
        // so it must not count as checked however the summary lists it.
        final Set<String> checked = checkedRefs(updateInfo, outdated);
        checked.removeAll(local);
        checked.addAll(outdated);

        final Map<String, ImageResult.State> services = new LinkedHashMap<>();
        final Set<String> localServices = new LinkedHashSet<>();
        for (final Map.Entry<String, String> entry : named(object, runtimeBody).entrySet()) {
            final String image = entry.getValue();
            // A service with no image of its own - one that is built rather than pulled, or one
            // whose config Arcane could not resolve - is UNKNOWN and never work. Recreating a
            // container on a guess is the one outcome this whole path exists to avoid.
            services.put(entry.getKey(),
                    image == null ? ImageResult.State.UNKNOWN : state(image, outdated, checked));
            if (image != null && local.contains(image)) {
                localServices.add(entry.getKey());
            }
        }
        return ImageResult.of(services, localServices);
    }

    /**
     * Service name to the image it resolves to, taken from wherever Arcane actually put a name.
     *
     * <p>{@code /runtime} first, because that is the payload which has them. The nameless
     * {@code services[]} of the updates body is read afterwards and only fills gaps, so a later
     * Arcane that starts naming them there needs no change here. An entry with a name and no image
     * is kept with a {@code null} image on purpose - "this service exists and nothing is known
     * about its image" is a different answer from "this service was never mentioned", and only the
     * first of the two should be reported.</p>
     */
    private static Map<String, String> named(final JsonObject updates, final String runtimeBody) {
        final Map<String, String> images = new LinkedHashMap<>();
        for (final JsonObject entry : entries(runtimeBody, "runtimeServices", "runtime_services",
                "services")) {
            final String name = text(entry, "name", "service", "serviceName");
            if (name != null) {
                images.put(name, text(entry, "image"));
            }
        }
        for (final JsonElement element : array(updates, "services")) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject service = element.getAsJsonObject();
            final String name = text(service, "name", "service", "serviceName");
            if (name != null) {
                images.putIfAbsent(name, text(service, "image"));
            }
        }
        return images;
    }

    /** The objects of the first array among {@code names} that the body carries, if any. */
    private static java.util.List<JsonObject> entries(final String body, final String... names) {
        final java.util.List<JsonObject> found = new java.util.ArrayList<>();
        if (body == null || body.isBlank()) {
            return found;
        }
        final JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (final RuntimeException unparseable) {
            return found;
        }
        if (!root.isJsonObject()) {
            return found;
        }
        for (final JsonElement element : array(unwrap(root.getAsJsonObject()), names)) {
            if (element.isJsonObject()) {
                found.add(element.getAsJsonObject());
            }
        }
        return found;
    }

    /**
     * The references Arcane holds only a {@code local} result for - i.e. never asked a registry
     * about, because the service carries a {@code build:} directive.
     */
    private static Set<String> localRefs(final JsonObject updateInfo) {
        final Set<String> refs = new LinkedHashSet<>();
        final JsonObject byRef = updateInfo == null
                ? null : object(updateInfo, "updateInfoByRef", "update_info_by_ref");
        if (byRef == null) {
            return refs;
        }
        for (final Map.Entry<String, JsonElement> entry : byRef.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject info = entry.getValue().getAsJsonObject();
            // The type is the statement; the empty latestDigest is the corroboration. Either alone
            // is enough - a future Arcane may rename the type and keep the empty digest, or the
            // other way round, and both readings mean the same thing: nothing was compared.
            final String type = text(info, "updateType", "update_type");
            final boolean unchecked = "local".equalsIgnoreCase(type)
                    || (text(info, "latestDigest", "latest_digest") == null
                        && text(info, "latestVersion", "latest_version") == null);
            if (unchecked && !bool(info, "hasUpdate", "has_update")) {
                refs.add(entry.getKey());
            }
        }
        return refs;
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
