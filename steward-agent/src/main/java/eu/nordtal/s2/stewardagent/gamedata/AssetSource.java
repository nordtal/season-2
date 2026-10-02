package eu.nordtal.s2.stewardagent.gamedata;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import eu.nordtal.s2.common.json.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/** The files of Mojang's client jar an icon is drawn from, by their path inside it. */
@FunctionalInterface
interface AssetSource {

    /** The bytes at {@code path}, such as {@code assets/minecraft/items/oak_log.json}, or none. */
    byte @Nullable [] bytes(String path);

    /** An item definition, a model or anything else JSON, or none when it is missing or broken. */
    default @Nullable JsonObject json(final String path) {
        final byte[] bytes = bytes(path);
        if (bytes == null) {
            return null;
        }
        try {
            return Json.decode(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
        } catch (final JsonParseException broken) {
            return null;
        }
    }

    /** A texture by its resource id, such as {@code minecraft:block/oak_log}, or none. */
    default @Nullable BufferedImage texture(final String id) {
        final byte[] bytes = bytes(path(id, "textures", ".png"));
        if (bytes == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (final IOException broken) {
            return null;
        }
    }

    /** Where a resource id's file is: model {@code block/stone} is {@code assets/minecraft/models/block/stone.json}. */
    static String path(final String id, final String kind, final String extension) {
        final int colon = id.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        final String rest = colon < 0 ? id : id.substring(colon + 1);
        return "assets/" + namespace + "/" + kind + "/" + rest + extension;
    }

    /** {@code block/stone} and {@code minecraft:block/stone} as one id. */
    static String id(final String reference) {
        final String lower = reference.toLowerCase(Locale.ROOT);
        return lower.indexOf(':') < 0 ? "minecraft:" + lower : lower;
    }
}
