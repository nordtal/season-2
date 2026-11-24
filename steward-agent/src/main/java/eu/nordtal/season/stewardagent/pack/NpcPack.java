package eu.nordtal.season.stewardagent.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.npc.NpcArtwork;
import eu.nordtal.season.database.npc.NpcSkin;
import eu.nordtal.season.packbuilder.vanilla.VanillaFacts;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * Writes the body texture of each NPC with a skin, and the portrait font of each with a skin or an uploaded artwork.
 * Both are keyed by the NPC's id; smp dresses it once the pack holds them. The font replaces the portrait glyphs of
 * {@code nordtal:npc}, one per panel height, which draw one texture.
 */
final class NpcPack {

    /** The picture's side inside the frame. */
    static final int PICTURE = 40;

    /** The frame's side: the picture and a bevel of 3 on each side. */
    static final int FRAME = PICTURE + 6;

    /** The texture's height: the frame padded below, so every panel height can raise it by its ascent. */
    static final int HEIGHT = 64;

    /** The texture every portrait glyph draws, beside the body texture. */
    static final String FILE = "portrait";

    /** The portrait glyphs of {@code nordtal:npc} in resource-pack/glyphs.json, which each NPC's font replaces. */
    static final List<Portrait> PORTRAITS = List.of(
            new Portrait(0xFE6E0, 4),
            new Portrait(0xFE6E1, 9),
            new Portrait(0xFE6E2, 14),
            new Portrait(0xFE6E3, 16),
            new Portrait(0xFE6E4, 19),
            new Portrait(0xFE6E5, 24),
            new Portrait(0xFE6E6, 26),
            new Portrait(0xFE6E7, 28),
            new Portrait(0xFE6E8, 36),
            new Portrait(0xFE6E9, 38),
            new Portrait(0xFE6EA, 48));

    private static final int SKIN = 64;

    private static final int OPAQUE = 255;

    /** Vanilla {@code generic_54.png} window and slot coordinates, matching {@code VanillaWindow} in build-logic. */
    static final int VANILLA_WIDTH = 176;

    static final int VANILLA_HEIGHT = 222;
    static final int VANILLA_EDGE = 5;
    static final int VANILLA_SLOT_X = 7;
    static final int VANILLA_SLOT_Y = 17;
    static final int VANILLA_SLOT_SIZE = 18;

    private static final int OUTLINE = 0xFF101014;

    private NpcPack() {}

    /** Reads vanilla's {@code generic_54.png} from the extracted {@code facts} zip. */
    static BufferedImage vanilla(final Path facts) throws IOException {
        final byte[] bytes = VanillaFacts.read(facts)
                .file(VanillaFacts.GENERIC_54)
                .orElseThrow(() -> new IOException("the vanilla facts have no " + VanillaFacts.GENERIC_54));
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("vanilla generic_54.png is not a readable PNG");
        }
        return image;
    }

    /**
     * Writes the files of every NPC in {@code skins} or {@code artworks} under {@code root}, laid out like the pack.
     * These are the body texture of a skin, and a portrait of the artwork, else of the skin's bust, framed from
     * {@code vanilla} ({@code generic_54.png}).
     */
    static void write(
            final Path root,
            final Map<UUID, NpcSkin> skins,
            final Map<UUID, NpcArtwork> artworks,
            final BufferedImage vanilla)
            throws IOException {
        final Set<UUID> npcs = new TreeSet<>(skins.keySet());
        npcs.addAll(artworks.keySet());
        for (final UUID npc : npcs) {
            final @Nullable NpcSkin skin = skins.get(npc);
            final @Nullable NpcArtwork artwork = artworks.get(npc);
            final Path body = asset(root, "textures", NpcSkin.bodyTexture(npc), ".png");
            final Path textures = Files.createDirectories(Objects.requireNonNull(body.getParent(), "folder"));
            final BufferedImage picture;
            if (artwork != null) {
                picture = artwork(Base64.getDecoder().decode(artwork.png()));
            } else {
                final NpcSkin dressed = Objects.requireNonNull(skin, "an NPC without artwork has a skin here");
                picture = bust(Base64.getDecoder().decode(dressed.png()), dressed.model() == NpcSkin.SkinModel.SLIM);
            }
            if (skin != null) {
                Files.write(body, Base64.getDecoder().decode(skin.png()));
            }
            ImageIO.write(
                    portrait(picture, vanilla),
                    "png",
                    textures.resolve(FILE + ".png").toFile());
            final Path font = asset(root, "font", NpcSkin.portraitFont(npc), ".json");
            Files.createDirectories(Objects.requireNonNull(font.getParent(), "folder"));
            Files.writeString(font, font(npc.toString()), StandardCharsets.UTF_8);
        }
    }

    /**
     * Brings an uploaded square picture to {@value #PICTURE} pixels square.
     * Each pixel is the average of the block of the upload it covers, so a large picture keeps its colours and a small
     * one grows in whole blocks.
     */
    static BufferedImage artwork(final byte[] png) throws IOException {
        final BufferedImage upload = ImageIO.read(new ByteArrayInputStream(png));
        if (upload == null || upload.getWidth() != upload.getHeight()) {
            throw new IOException("an NPC artwork is not a square PNG");
        }
        final int side = upload.getWidth();
        final BufferedImage picture = blank(PICTURE, PICTURE);
        for (int y = 0; y < PICTURE; y++) {
            for (int x = 0; x < PICTURE; x++) {
                picture.setRGB(x, y, average(upload, span(x, side), span(y, side)));
            }
        }
        return picture;
    }

    /** Returns the first and the end column of the upload's block under picture column {@code at}. */
    private static int[] span(final int at, final int side) {
        final int from = at * side / PICTURE;
        return new int[] {from, Math.max(from + 1, (at + 1) * side / PICTURE)};
    }

    /** Averages the colours weighted by their alpha, so a clear pixel's hidden colour does not darken an edge. */
    private static int average(final BufferedImage image, final int[] columns, final int[] rows) {
        final long[] colour = new long[3];
        long alpha = 0;
        int count = 0;
        for (int y = rows[0]; y < rows[1]; y++) {
            for (int x = columns[0]; x < columns[1]; x++) {
                final int argb = image.getRGB(x, y);
                final int opacity = argb >>> 24;
                for (int channel = 0; channel < 3; channel++) {
                    colour[channel] += (long) (argb >>> (8 * channel) & 0xFF) * opacity;
                }
                alpha += opacity;
                count++;
            }
        }
        int argb = (int) Math.round(alpha / (double) count) << 24;
        for (int channel = 0; alpha > 0 && channel < 3; channel++) {
            argb |= (int) Math.round(colour[channel] / (double) alpha) << (8 * channel);
        }
        return argb;
    }

    /** Returns where the pack keeps the {@code kind} asset called {@code key}, under {@code root}. */
    static Path asset(final Path root, final String kind, final String key, final String extension) {
        final int colon = key.indexOf(':');
        return root.resolve("assets")
                .resolve(key.substring(0, colon))
                .resolve(kind)
                .resolve(key.substring(colon + 1) + extension);
    }

    /**
     * Draws a chibi bust of a 64 by 64 skin, {@value #PICTURE} pixels square.
     * The front of the head and its hat layer are three times as large, the upper body and arms twice, with a dark
     * outline around the figure and light from above: the default portrait's drawing of the design kit's skin.
     */
    static BufferedImage bust(final byte[] png, final boolean slim) throws IOException {
        final BufferedImage skin = ImageIO.read(new ByteArrayInputStream(png));
        if (skin == null || skin.getWidth() != SKIN || skin.getHeight() != SKIN) {
            throw new IOException("an NPC skin is not a 64 by 64 PNG");
        }
        final int arm = slim ? 3 : 4;
        final BufferedImage head = scaled(part(skin, 8, 8, 8, 8, 40, 8), 3);
        final BufferedImage torso = scaled(part(skin, 20, 20, 8, 6, 20, 36), 2);
        final BufferedImage right = scaled(part(skin, 44, 20, arm, 6, 44, 36), 2);
        final BufferedImage left = scaled(part(skin, 36, 52, arm, 6, 52, 52), 2);
        final BufferedImage figure = blank(PICTURE, PICTURE);
        final int middle = PICTURE / 2;
        final int body = PICTURE - 12;
        blit(figure, shaded(right, 0.78), middle - 8 - 2 * arm, body + 1);
        blit(figure, shaded(left, 0.70), middle + 8, body + 1);
        blit(figure, shaded(torso, 0.88), middle - 8, body);
        final int top = body - 23;
        blit(figure, head, middle - 12, top);
        for (int x = middle - 12; x < middle + 12; x++) {
            figure.setRGB(x, top, scaledColour(figure.getRGB(x, top), 1.12));
            figure.setRGB(x, top + 23, scaledColour(figure.getRGB(x, top + 23), 0.8));
        }
        final BufferedImage outlined = blank(PICTURE, PICTURE);
        for (int y = 0; y < PICTURE; y++) {
            for (int x = 0; x < PICTURE; x++) {
                if (alpha(figure.getRGB(x, y)) == 0 && touchesInk(figure, x, y)) {
                    outlined.setRGB(x, y, OUTLINE);
                }
            }
        }
        blit(outlined, figure, 0, 0);
        final BufferedImage lowered = blank(PICTURE, PICTURE);
        blit(lowered, outlined, 0, 1);
        return lowered;
    }

    /**
     * Frames a {@value #PICTURE} square picture in vanilla's window bevel and slot well, padded to {@value #HEIGHT}.
     * The picture lies over the whole well so an uploaded artwork shows every pixel; where clear, the well shows.
     */
    static BufferedImage portrait(final BufferedImage picture, final BufferedImage vanilla) {
        final BufferedImage frame = blank(FRAME, HEIGHT);
        vanillaWindow(vanilla, frame, FRAME, FRAME);
        vanillaSlotWell(vanilla, frame, 3, 3, PICTURE, PICTURE);
        blit(frame, picture, 3, 3);
        return frame;
    }

    /** Copies vanilla's 5 px corners, edges and fill from {@code generic_54.png} onto {@code target}. */
    private static void vanillaWindow(
            final BufferedImage vanilla, final BufferedImage target, final int width, final int height) {
        for (int y = 0; y < height; y++) {
            final int sy = y < VANILLA_EDGE ? y : (y >= height - VANILLA_EDGE ? VANILLA_HEIGHT - (height - y) : 10);
            for (int x = 0; x < width; x++) {
                final int sx = x < VANILLA_EDGE ? x : (x >= width - VANILLA_EDGE ? VANILLA_WIDTH - (width - x) : 10);
                target.setRGB(x, y, vanilla.getRGB(sx, sy));
            }
        }
    }

    /** Draws vanilla's sunk slot bevel from {@code generic_54.png} stretched to {@code width} by {@code height}. */
    private static void vanillaSlotWell(
            final BufferedImage vanilla,
            final BufferedImage target,
            final int x,
            final int y,
            final int width,
            final int height) {
        final int dark = vanilla.getRGB(VANILLA_SLOT_X, VANILLA_SLOT_Y);
        final int fill = vanilla.getRGB(VANILLA_SLOT_X + 1, VANILLA_SLOT_Y + 1);
        final int corner = vanilla.getRGB(VANILLA_SLOT_X + VANILLA_SLOT_SIZE - 1, VANILLA_SLOT_Y);
        final int light =
                vanilla.getRGB(VANILLA_SLOT_X + VANILLA_SLOT_SIZE - 1, VANILLA_SLOT_Y + VANILLA_SLOT_SIZE - 1);
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                final int color;
                if ((dx == width - 1 && dy == 0) || (dx == 0 && dy == height - 1)) {
                    color = corner;
                } else if (dx == 0 || dy == 0) {
                    color = dark;
                } else if (dx == width - 1 || dy == height - 1) {
                    color = light;
                } else {
                    color = fill;
                }
                target.setRGB(x + dx, y + dy, color);
            }
        }
    }

    /** Returns a part of the skin, {@code width} by {@code height} from its top left, with its outer layer on top. */
    private static BufferedImage part(
            final BufferedImage skin,
            final int x,
            final int y,
            final int width,
            final int height,
            final int outerX,
            final int outerY) {
        final BufferedImage part = blank(width, height);
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                part.setRGB(i, j, skin.getRGB(x + i, y + j));
                over(part, i, j, skin.getRGB(outerX + i, outerY + j));
            }
        }
        return part;
    }

    private static BufferedImage scaled(final BufferedImage image, final int factor) {
        final BufferedImage scaled = blank(image.getWidth() * factor, image.getHeight() * factor);
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                scaled.setRGB(x, y, image.getRGB(x / factor, y / factor));
            }
        }
        return scaled;
    }

    private static BufferedImage shaded(final BufferedImage image, final double factor) {
        final BufferedImage shaded = blank(image.getWidth(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                shaded.setRGB(x, y, scaledColour(image.getRGB(x, y), factor));
            }
        }
        return shaded;
    }

    /** Returns {@code argb} with its colour channels times {@code factor}, or as it is where it is clear. */
    private static int scaledColour(final int argb, final double factor) {
        if (alpha(argb) == 0) {
            return argb;
        }
        int scaled = argb & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            scaled |= (int) Math.min(255, Math.round((argb >> shift & 0xFF) * factor)) << shift;
        }
        return scaled;
    }

    private static boolean touchesInk(final BufferedImage image, final int x, final int y) {
        final int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (final int[] side : sides) {
            final int nx = x + side[0];
            final int ny = y + side[1];
            if (nx >= 0
                    && nx < image.getWidth()
                    && ny >= 0
                    && ny < image.getHeight()
                    && alpha(image.getRGB(nx, ny)) != 0) {
                return true;
            }
        }
        return false;
    }

    private static void blit(final BufferedImage target, final BufferedImage source, final int x, final int y) {
        for (int j = 0; j < source.getHeight(); j++) {
            for (int i = 0; i < source.getWidth(); i++) {
                over(target, x + i, y + j, source.getRGB(i, j));
            }
        }
    }

    /** Lays {@code argb} over the pixel at {@code x}, {@code y} by its alpha; outside the image it is lost. */
    private static void over(final BufferedImage image, final int x, final int y, final int argb) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return;
        }
        final int alpha = alpha(argb);
        if (alpha == OPAQUE) {
            image.setRGB(x, y, argb);
        } else if (alpha != 0) {
            image.setRGB(x, y, sourceOver(image.getRGB(x, y), argb));
        }
    }

    /** Returns {@code top} laid over {@code below} by source-over compositing, both with straight alpha. */
    static int sourceOver(final int below, final int top) {
        final double a = alpha(top) / 255.0;
        final double b = alpha(below) / 255.0 * (1 - a);
        final double out = a + b;
        if (out == 0) {
            return 0;
        }
        int argb = (int) Math.round(out * 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            final double channel = ((top >> shift & 0xFF) * a + (below >> shift & 0xFF) * b) / out;
            argb |= (int) Math.round(channel) << shift;
        }
        return argb;
    }

    private static int alpha(final int argb) {
        return argb >>> 24;
    }

    private static BufferedImage blank(final int width, final int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static String font(final String npc) {
        final JsonArray providers = new JsonArray();
        // A move after the portrait joins its run, so this font resolves the moves as nordtal:npc does.
        final JsonObject moves = new JsonObject();
        moves.addProperty("type", "reference");
        moves.addProperty("id", "nordtal:ui_space");
        providers.add(moves);
        for (final Portrait portrait : PORTRAITS) {
            final JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", "nordtal:npc/" + npc + "/" + FILE + ".png");
            provider.addProperty("height", HEIGHT);
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

    /** One portrait glyph: its code point and its ascent, which raises the portrait onto one panel height. */
    record Portrait(int code, int ascent) {}
}
