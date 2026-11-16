package eu.nordtal.season.stewardagent.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.npc.NpcSkin;
import eu.nordtal.season.database.npc.NpcStore;
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
import java.util.TreeMap;
import java.util.UUID;

/**
 * What the pack is built from besides its fixed files: smp's wheel prizes, the wheel's texts and the NPC skins.
 *
 * @param prizes the prize list as the pack builder reads it, a JSON list of {@code item} and {@code amount}
 * @param texts the wheel's text document as JSON
 * @param label the spin button's label by language, the one text of the document the pack draws
 * @param npcs the uploaded skin of each NPC that has one, as a JSON object of skin ids by NPC id
 * @param skins the skins {@code npcs} names, by NPC id, to draw from; empty for inputs read back from a build record
 */
record PackInputs(String prizes, String texts, Map<String, String> label, String npcs, Map<UUID, NpcSkin> skins) {

    static final String SERVICE = "smp";
    static final String GROUP = "wheel";
    static final String PRIZES = "wheel-prizes";
    static final String DOCUMENT = "wheel";

    PackInputs {
        label = Map.copyOf(label);
        skins = Map.copyOf(skins);
    }

    /** Inputs without NPC skins to draw, as a build record holds them. */
    PackInputs(final String prizes, final String texts, final Map<String, String> label, final String npcs) {
        this(prizes, texts, label, npcs, Map.of());
    }

    /**
     * Reads the inputs as smp applies them.
     *
     * @param shippedPrizes smp's default prizes as this release ships them, for an smp that never published its group
     * @param npcs the NPCs, whose uploaded skins the pack draws
     * @throws IllegalArgumentException if a stored value cannot be read
     */
    static PackInputs read(
            final SettingStore settings,
            final AppliedDocuments documents,
            final Path shippedPrizes,
            final NpcStore npcs)
            throws IOException {
        final String texts = documents.applied(SERVICE, DOCUMENT).document().json();
        final Map<UUID, NpcSkin> skins = npcs.skins();
        return new PackInputs(prizes(settings, shippedPrizes), texts, label(texts), skinIds(skins), skins);
    }

    /** Returns the skin id of each NPC as a JSON object, which is all the pack's NPC files depend on. */
    static String skinIds(final Map<UUID, NpcSkin> skins) {
        final JsonObject ids = new JsonObject();
        new TreeMap<>(skins)
                .forEach(
                        (npc, skin) -> ids.addProperty(npc.toString(), skin.id().toString()));
        return ids.toString();
    }

    /** Returns whether a build from {@code other} draws the same pack, however the prizes are spaced or ordered. */
    boolean drawsSame(final PackInputs other) {
        return label.equals(other.label)
                && JsonParser.parseString(prizes).equals(JsonParser.parseString(other.prizes))
                && JsonParser.parseString(npcs).equals(JsonParser.parseString(other.npcs));
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
