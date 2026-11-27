package eu.nordtal.season.packbuilder.map;

import eu.nordtal.season.common.Platform;
import eu.nordtal.season.packbuilder.json.Json;
import eu.nordtal.season.packbuilder.png.Png;
import eu.nordtal.season.packbuilder.zip.ReproducibleZip;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The map pack: the play area's images as bitmap glyphs, one font for each number of blocks per pixel.
 *
 * Every language gets the same map pack, so the language pack keeps its hash when only the map changes.
 */
public final class MapPack {

    /** The blocks per pixel of the two images. */
    public static final List<Integer> SCALES = List.of(8, 16);

    /** The pixels along each side of one glyph's texture. */
    public static final int GLYPH = 64;

    /** The codepoint of the glyph in the north-west corner; the others follow row by row. */
    public static final int FIRST_CODEPOINT = 0xF0000;

    /** Codepoint offset for the same tiles lowered by one tile height ({@code ascent = -GLYPH}). */
    public static final int SECOND_ROW_OFFSET = 0x1000;

    private MapPack() {}

    /** Returns the zip of the map pack that BlueMap's {@code tiles} folder draws for the play area. */
    public static byte[] build(final Path tiles) throws IOException {
        return ReproducibleZip.write(files(MapRender.draw(tiles, MapArea.PLAY, SCALES)));
    }

    /** Returns the pack's files by path for {@code images}, keyed by blocks per pixel. */
    public static Map<String, byte[]> files(final Map<Integer, BufferedImage> images) {
        final Map<String, byte[]> files = new TreeMap<>();
        files.put("pack.mcmeta", Json.bytes(mcmeta()));
        for (final Map.Entry<Integer, BufferedImage> image : images.entrySet()) {
            final int scale = image.getKey();
            final BufferedImage whole = image.getValue();
            final int columns = glyphs(whole.getWidth());
            final int rows = glyphs(whole.getHeight());
            final List<Object> providers = new ArrayList<>();
            for (int ty = 0; ty < rows; ty++) {
                for (int tx = 0; tx < columns; tx++) {
                    final String texture = "map/" + scale + "/" + tx + "_" + ty + ".png";
                    files.put("assets/nordtal/textures/" + texture, Png.png(glyph(whole, tx, ty)));
                    final int code = codepoint(columns, tx, ty);
                    providers.add(provider("nordtal:" + texture, 0, code));
                    providers.add(provider("nordtal:" + texture, -GLYPH, code + SECOND_ROW_OFFSET));
                }
            }
            files.put("assets/nordtal/font/map_" + scale + ".json", Json.bytes(Map.of("providers", providers)));
        }
        return files;
    }

    /** Returns the glyphs along a side of {@code pixels}; the last one is padded with transparency. */
    static int glyphs(final int pixels) {
        return Math.ceilDiv(pixels, GLYPH);
    }

    /** Returns the codepoint of the glyph in column {@code tx} and row {@code ty} of a map {@code columns} wide. */
    public static int codepoint(final int columns, final int tx, final int ty) {
        return FIRST_CODEPOINT + ty * columns + tx;
    }

    private static BufferedImage glyph(final BufferedImage whole, final int tx, final int ty) {
        final BufferedImage glyph = new BufferedImage(GLYPH, GLYPH, BufferedImage.TYPE_INT_ARGB);
        final int width = Math.min(GLYPH, whole.getWidth() - tx * GLYPH);
        final int height = Math.min(GLYPH, whole.getHeight() - ty * GLYPH);
        final int[] pixels = whole.getRGB(tx * GLYPH, ty * GLYPH, width, height, null, 0, width);
        glyph.setRGB(0, 0, width, height, pixels, 0, width);
        return glyph;
    }

    private static Map<String, Object> provider(final String file, final int ascent, final int codepoint) {
        final Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("type", "bitmap");
        provider.put("file", file);
        provider.put("ascent", ascent);
        provider.put("height", GLYPH);
        provider.put("chars", List.of(Character.toString(codepoint)));
        return provider;
    }

    private static Map<String, Object> mcmeta() {
        final Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("min_format", Platform.PACK_FORMAT);
        pack.put("max_format", Platform.PACK_FORMAT);
        pack.put("description", "§9Nordtal map");
        return Map.of("pack", pack);
    }
}
