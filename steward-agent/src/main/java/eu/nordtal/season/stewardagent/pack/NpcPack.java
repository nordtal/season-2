package eu.nordtal.season.stewardagent.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.npc.NpcSkin;
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
 * Writes each skinned NPC's body texture and portrait font, keyed by its id; smp dresses it once the pack holds them.
 * The font replaces the portrait glyphs of {@code nordtal:npc}, one per panel height, which draw one texture.
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

    private static final int BLACK = 0xFF000000;

    private static final int WHITE = 0xFFFFFFFF;

    private static final int FILL = 0xFFC6C6C6;

    private static final int SHADE = 0xFF555555;

    private static final int WELL_TOP = 0x9A9A9A;

    private static final int WELL_LOW = 0x6E6E6E;

    /** The line that sinks the well's top and left edge: #373737 at alpha 160. */
    private static final int WELL_EDGE = 0xA0373737;

    private static final int OUTLINE = 0xFF101014;

    private NpcPack() {}

    /** Writes the files of every NPC in {@code skins} under {@code root}, a folder laid out like the pack. */
    static void write(final Path root, final Map<UUID, NpcSkin> skins) throws IOException {
        for (final Map.Entry<UUID, NpcSkin> entry : new TreeMap<>(skins).entrySet()) {
            final String npc = entry.getKey().toString();
            final byte[] png = Base64.getDecoder().decode(entry.getValue().png());
            final Path body = asset(root, "textures", NpcSkin.bodyTexture(entry.getKey()), ".png");
            final Path textures = Files.createDirectories(Objects.requireNonNull(body.getParent(), "folder"));
            Files.write(body, png);
            ImageIO.write(
                    portrait(bust(png, entry.getValue().model() == NpcSkin.SkinModel.SLIM)),
                    "png",
                    textures.resolve(FILE + ".png").toFile());
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

    /** Frames a {@value #PICTURE} square picture as the panel's bevel and well, padded below to {@value #HEIGHT}. */
    static BufferedImage portrait(final BufferedImage picture) {
        final BufferedImage frame = blank(FRAME, HEIGHT);
        bevel(frame);
        for (int y = 0; y < PICTURE; y++) {
            final double t = y / (double) (PICTURE - 1);
            final int colour = OPAQUE << 24 | mix(WELL_TOP, WELL_LOW, t);
            for (int x = 0; x < PICTURE; x++) {
                frame.setRGB(3 + x, 3 + y, colour);
            }
        }
        blit(frame, picture, 3, 3);
        for (int i = 0; i < PICTURE; i++) {
            over(frame, 3 + i, 3, WELL_EDGE);
        }
        for (int i = 0; i < PICTURE; i++) {
            over(frame, 3, 3 + i, WELL_EDGE);
        }
        return frame;
    }

    /** Draws the chest window's outline, light and shade on the top {@value #FRAME} rows, as the pack's panels do. */
    private static void bevel(final BufferedImage frame) {
        final int n = FRAME;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                frame.setRGB(x, y, FILL);
            }
        }
        for (int i = 0; i < n; i++) {
            frame.setRGB(i, 0, BLACK);
            frame.setRGB(i, n - 1, BLACK);
            frame.setRGB(0, i, BLACK);
            frame.setRGB(n - 1, i, BLACK);
        }
        for (int y = 1; y < n - 1; y++) {
            for (final int x : new int[] {1, 2}) {
                frame.setRGB(x, y, y <= n - 4 ? WHITE : SHADE);
            }
            for (final int x : new int[] {n - 3, n - 2}) {
                frame.setRGB(x, y, y >= 3 ? SHADE : WHITE);
            }
        }
        for (int x = 1; x < n - 1; x++) {
            for (final int y : new int[] {1, 2}) {
                if (x <= n - 4) {
                    frame.setRGB(x, y, WHITE);
                }
            }
            for (final int y : new int[] {n - 3, n - 2}) {
                if (x >= 3) {
                    frame.setRGB(x, y, SHADE);
                }
            }
        }
        frame.setRGB(n - 4, n - 4, SHADE);
        corners(frame);
        for (int y = 3; y < n - 3; y++) {
            for (int x = 3; x < n - 3; x++) {
                frame.setRGB(x, y, BLACK);
            }
        }
    }

    /** Rounds the frame's corners: clear outside, black inside, and the fill where light and shade meet. */
    private static void corners(final BufferedImage frame) {
        final int n = FRAME;
        final int[][] clear = {
            {0, 0},
            {1, 0},
            {0, 1},
            {n - 1, 0},
            {n - 2, 0},
            {n - 1, 1},
            {0, n - 1},
            {1, n - 1},
            {0, n - 2},
            {n - 1, n - 1},
            {n - 2, n - 1},
            {n - 1, n - 2}
        };
        for (final int[] corner : clear) {
            frame.setRGB(corner[0], corner[1], 0);
        }
        for (final int[] corner : new int[][] {{1, 1}, {n - 2, 1}, {1, n - 2}, {n - 2, n - 2}}) {
            frame.setRGB(corner[0], corner[1], BLACK);
        }
        frame.setRGB(n - 3, 2, FILL);
        frame.setRGB(2, n - 3, FILL);
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

    /** Returns the colour {@code share} of the way from {@code from} to {@code to}, both without alpha. */
    private static int mix(final int from, final int to, final double share) {
        int mixed = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            final int a = from >> shift & 0xFF;
            final int b = to >> shift & 0xFF;
            mixed |= (int) Math.round(a + (b - a) * share) << shift;
        }
        return mixed;
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
