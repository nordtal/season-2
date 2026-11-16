package eu.nordtal.season.stewardagent.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.npc.NpcSkin;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Writes the pack files of each NPC with an uploaded skin: its body texture and its portrait font.
 * The figure's profile names the texture, and the font replaces the three portrait glyphs of {@code nordtal:npc}.
 * Both are keyed by the NPC's id, and smp dresses an NPC in a skin only once the active pack holds it.
 */
final class NpcPack {

    /** The face's side on the panel: the skin's 8 by 8 face drawn three times as large. */
    private static final int FACE = 24;

    private static final int SKIN = 64;

    /** The portrait glyphs of {@code nordtal:npc} in resource-pack/glyphs.json, which each NPC's font replaces. */
    static final List<Portrait> PORTRAITS = List.of(
            new Portrait("portrait_speak", 0xFE6A2, 24, 13),
            new Portrait("portrait_2", 0xFE6A5, 44, 44),
            new Portrait("portrait_3", 0xFE6A8, 64, 64));

    private NpcPack() {}

    /** Writes the files of every NPC in {@code skins} under {@code root}, a folder laid out like the pack. */
    static void write(final Path root, final Map<UUID, NpcSkin> skins) throws IOException {
        for (final Map.Entry<UUID, NpcSkin> entry : new TreeMap<>(skins).entrySet()) {
            final String npc = entry.getKey().toString();
            final byte[] png = Base64.getDecoder().decode(entry.getValue().png());
            final Path body = asset(root, "textures", NpcSkin.bodyTexture(entry.getKey()), ".png");
            final Path textures = Files.createDirectories(Objects.requireNonNull(body.getParent(), "folder"));
            Files.write(body, png);
            final BufferedImage face = face(png);
            for (final Portrait portrait : PORTRAITS) {
                ImageIO.write(
                        padded(face, portrait.height()),
                        "png",
                        textures.resolve(portrait.file() + ".png").toFile());
            }
            final Path font = asset(root, "font", NpcSkin.portraitFont(entry.getKey()), ".json");
            Files.createDirectories(Objects.requireNonNull(font.getParent(), "folder"));
            Files.writeString(font, font(npc), StandardCharsets.UTF_8);
        }
    }

    /** Returns where the pack keeps the {@code kind} asset called {@code key}, under {@code root}. */
    static Path asset(final Path root, final String kind, final String key, final String extension) {
        final int colon = key.indexOf(':');
        return root.resolve("assets")
                .resolve(key.substring(0, colon))
                .resolve(kind)
                .resolve(key.substring(colon + 1) + extension);
    }

    /** Draws the face and the hat layer of a 64 by 64 skin three times as large, pixel for pixel. */
    static BufferedImage face(final byte[] png) throws IOException {
        final BufferedImage skin = ImageIO.read(new ByteArrayInputStream(png));
        if (skin == null || skin.getWidth() != SKIN || skin.getHeight() != SKIN) {
            throw new IOException("an NPC skin is not a 64 by 64 PNG");
        }
        final BufferedImage face = new BufferedImage(FACE, FACE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D drawing = face.createGraphics();
        try {
            drawing.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            drawing.drawImage(skin, 0, 0, FACE, FACE, 8, 8, 16, 16, null);
            drawing.drawImage(skin, 0, 0, FACE, FACE, 40, 8, 48, 16, null);
        } finally {
            drawing.dispose();
        }
        return face;
    }

    /** The face on top of a transparent sheet {@code height} tall, as the pack's own portraits are padded below. */
    private static BufferedImage padded(final BufferedImage face, final int height) {
        final BufferedImage padded = new BufferedImage(FACE, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D drawing = padded.createGraphics();
        try {
            drawing.drawImage(face, 0, 0, null);
        } finally {
            drawing.dispose();
        }
        return padded;
    }

    private static String font(final String npc) {
        final JsonArray providers = new JsonArray();
        for (final Portrait portrait : PORTRAITS) {
            final JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", "nordtal:npc/" + npc + "/" + portrait.file() + ".png");
            provider.addProperty("height", portrait.height());
            provider.addProperty("ascent", portrait.ascent());
            final JsonArray chars = new JsonArray();
            chars.add(Character.toString(portrait.code()));
            provider.add("chars", chars);
            providers.add(provider);
        }
        final JsonObject font = new JsonObject();
        font.add("providers", providers);
        return font.toString();
    }

    /** One portrait glyph: its file name, code point, height and ascent. */
    record Portrait(String file, int code, int height, int ascent) {}
}
