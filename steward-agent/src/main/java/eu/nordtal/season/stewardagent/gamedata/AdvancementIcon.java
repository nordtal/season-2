package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The icon an advancement shows when its item carries banner patterns, read from the client jar's data.
 * An item's own icon cannot show them, so the sheet gives the advancement a slot under its own id.
 *
 * @param item the icon's item, such as {@code minecraft:white_banner}
 * @param patterns the banner patterns it carries, bottom first
 */
record AdvancementIcon(String item, List<BannerLayer> patterns) {

    /** The icon of {@code advancement}, or none when it has no definition or its item carries no banner patterns. */
    static @Nullable AdvancementIcon of(final AssetSource assets, final String advancement) {
        final JsonObject definition =
                assets.json(AssetSource.dataPath(AssetSource.id(advancement), "advancement", ".json"));
        if (definition == null
                || !(definition.get("display") instanceof JsonObject display)
                || !(display.get("icon") instanceof JsonObject icon)
                || !(icon.get("id") instanceof JsonPrimitive item)
                || !(icon.get("components") instanceof JsonObject components)) {
            return null;
        }
        final JsonElement declared = components.has("minecraft:banner_patterns")
                ? components.get("minecraft:banner_patterns")
                : components.get("banner_patterns");
        if (!(declared instanceof JsonArray layers)) {
            return null;
        }
        final List<BannerLayer> patterns = new ArrayList<>();
        for (final JsonElement layer : layers) {
            if (!(layer instanceof JsonObject declaredLayer)
                    || !(declaredLayer.get("color") instanceof JsonPrimitive dye)) {
                continue;
            }
            final String texture = textureOf(assets, declaredLayer.get("pattern"));
            if (texture != null) {
                patterns.add(new BannerLayer(texture, dye.getAsString()));
            }
        }
        return patterns.isEmpty()
                ? null
                : new AdvancementIcon(AssetSource.id(item.getAsString()), List.copyOf(patterns));
    }

    /** The texture of a pattern named by id or declared inline: its asset id under {@code entity/banner}. */
    private static @Nullable String textureOf(final AssetSource assets, final @Nullable JsonElement pattern) {
        final JsonObject declared;
        if (pattern instanceof JsonObject inline) {
            declared = inline;
        } else if (pattern instanceof JsonPrimitive named) {
            declared =
                    assets.json(AssetSource.dataPath(AssetSource.id(named.getAsString()), "banner_pattern", ".json"));
        } else {
            return null;
        }
        final String fallback = pattern instanceof JsonPrimitive named ? named.getAsString() : null;
        final String asset =
                declared != null && declared.get("asset_id") instanceof JsonPrimitive id ? id.getAsString() : fallback;
        if (asset == null) {
            return null;
        }
        final String id = AssetSource.id(asset);
        final int colon = id.indexOf(':');
        return id.substring(0, colon + 1) + "entity/banner/" + id.substring(colon + 1);
    }
}
