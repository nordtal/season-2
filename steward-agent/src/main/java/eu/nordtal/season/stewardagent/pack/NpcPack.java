package eu.nordtal.season.stewardagent.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.season.database.npc.NpcArtwork;
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

    /**
     * Writes the files of every NPC in {@code skins} or {@code artworks} under {@code root}, laid out like the pack.
     * These are the body texture of a skin, and a portrait of the artwork, else of the skin's bust.
     */
    static void write(final Path root, final Map<UUID, NpcSkin> skins, final Map<UUID, NpcArtwork> artworks)
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
                    portrait(picture), "png", textures.resolve(FILE + ".png").toFile());
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
     * Frames a {@value #PICTURE} square picture as the panel's bevel and well, padded below to {@value #HEIGHT}.
     * The picture lies over the whole well, its sunk edge included, so an uploaded picture shows every pixel; where it
     * is clear, the well shows.
     */
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
        for (int i = 0; i < PICTURE; i++) {
            over(frame, 3 + i, 3, WELL_EDGE);
        }
        for (int i = 0; i < PICTURE; i++) {
            over(frame, 3, 3 + i, WELL_EDGE);
        }
        blit(frame, picture, 3, 3);
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
        corners(frame);
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
