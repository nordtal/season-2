package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import eu.nordtal.season.common.json.Json;
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

    byte @Nullable [] bytes(String path);

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
        return located("assets", id, kind, extension);
    }

    /** Where a data pack entry's file is: advancement {@code story/root} is {@code data/minecraft/advancement/...}. */
    static String dataPath(final String id, final String kind, final String extension) {
        return located("data", id, kind, extension);
    }

    private static String located(final String root, final String id, final String kind, final String extension) {
        final int colon = id.indexOf(':');
        final String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        final String rest = colon < 0 ? id : id.substring(colon + 1);
        return root + "/" + namespace + "/" + kind + "/" + rest + extension;
    }

    /** {@code block/stone} and {@code minecraft:block/stone} as one id. */
    static String id(final String reference) {
        final String lower = reference.toLowerCase(Locale.ROOT);
        return lower.indexOf(':') < 0 ? "minecraft:" + lower : lower;
    }
}
