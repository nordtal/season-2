package eu.nordtal.season.stewardagent.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.packbuilder.json.Json;
import eu.nordtal.season.packbuilder.wheel.WheelPack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the pack is built from besides its fixed files: smp's wheel prizes and the wheel's texts as they apply now.
 *
 * @param prizes the prize list as the pack builder reads it, a JSON list of {@code item} and {@code amount}
 * @param texts the wheel's text document as JSON
 * @param label the spin button's label by language, the one text of the document the pack draws
 */
record PackInputs(String prizes, String texts, Map<String, String> label) {

    static final String SERVICE = "smp";
    static final String GROUP = "wheel";
    static final String PRIZES = "wheel-prizes";
    static final String DOCUMENT = "wheel";

    PackInputs {
        label = Map.copyOf(label);
    }

    /**
     * Reads the inputs as smp applies them.
     *
     * @param shippedPrizes smp's default prizes as this release ships them, for an smp that never published its group
     * @throws IllegalArgumentException if a stored value cannot be read
     */
    static PackInputs read(final SettingStore settings, final AppliedDocuments documents, final Path shippedPrizes)
            throws IOException {
        final String texts = documents.applied(SERVICE, DOCUMENT).document().json();
        return new PackInputs(prizes(settings, shippedPrizes), texts, label(texts));
    }

    /** Returns whether a build from {@code other} draws the same pack. */
    boolean drawsSame(final PackInputs other) {
        return prizes.equals(other.prizes) && label.equals(other.label);
    }

    /** The prize list: an admin's, else the defaults smp published, else the ones this release ships. */
    static String prizes(final SettingStore settings, final Path shippedPrizes) throws IOException {
        final Optional<String> stored = settings.overrides(List.of(SERVICE)).stream()
                .filter(value -> GROUP.equals(value.group()) && PRIZES.equals(value.path()))
                .map(SettingStore.Value::value)
                .findFirst();
        if (stored.isPresent()) {
            return normalized(JsonParser.parseString(stored.get()));
        }
        final Optional<JsonElement> published = settings.group(SERVICE, GROUP)
                .map(group -> JsonParser.parseString(group.defaults()))
                .filter(JsonElement::isJsonObject)
                .map(defaults -> defaults.getAsJsonObject().get(PRIZES));
        if (published.isPresent()) {
            return normalized(published.get());
        }
        return normalized(JsonParser.parseString(Files.readString(shippedPrizes, StandardCharsets.UTF_8)));
    }

    /** Keeps of each prize what the pack draws, {@code item} and {@code amount}, with smp's default amount of 1. */
    static String normalized(final JsonElement prizes) {
        if (!prizes.isJsonArray()) {
            throw new IllegalArgumentException("the wheel prizes are no list: " + prizes);
        }
        final JsonArray out = new JsonArray();
        for (final JsonElement each : prizes.getAsJsonArray()) {
            if (!each.isJsonObject() || !each.getAsJsonObject().has("item")) {
                throw new IllegalArgumentException("a wheel prize has no item: " + each);
            }
            final JsonObject prize = each.getAsJsonObject();
            final JsonObject kept = new JsonObject();
            kept.addProperty("item", prize.get("item").getAsString());
            kept.addProperty("amount", prize.has("amount") ? prize.get("amount").getAsInt() : 1);
            out.add(kept);
        }
        return out.toString();
    }

    private static Map<String, String> label(final String texts) {
        return WheelPack.labels(Json.parse(texts.getBytes(StandardCharsets.UTF_8)));
    }
}
