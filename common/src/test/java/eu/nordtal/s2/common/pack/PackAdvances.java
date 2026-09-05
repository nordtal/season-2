package eu.nordtal.s2.common.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import eu.nordtal.s2.common.RepositoryRoot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives every glyph's advance from a font file and its PNGs, the way the client does.
 *
 * <p>One rule, three tests: a {@code space} provider's number, or a bitmap cell's rightmost column
 * with any alpha, plus one for that column and one the client adds after every glyph - scaled by
 * {@code height / cellHeight}, which is 1 for everything this pack draws. The first provider to
 * declare a code point wins. {@code BoardFrameTest} and {@code MenuTitleTest} each had their own
 * copy of this walk; {@code BossBarAdvancesTest} and {@code BossBarLineTest} share this one.</p>
 */
public final class PackAdvances {

    private PackAdvances() {
    }

    /**
     * @param fontFile the font's path from the repository root
     * @return code point to advance, in declaration order
     */
    public static Map<Integer, Integer> of(final String fontFile) {
        final Map<Integer, Integer> table = new LinkedHashMap<>();
        final JsonObject root = JsonParser.parseString(RepositoryRoot.read(fontFile)).getAsJsonObject();
        for (final JsonElement element : root.getAsJsonArray("providers")) {
            final JsonObject provider = element.getAsJsonObject();
            if ("space".equals(provider.get("type").getAsString())) {
                for (final Map.Entry<String, JsonElement> advance
                        : provider.getAsJsonObject("advances").entrySet()) {
                    advance.getKey().codePoints().forEach(codePoint ->
                            table.putIfAbsent(codePoint, advance.getValue().getAsInt()));
                }
                continue;
            }

            final BufferedImage image = read(FontFile.texturePath(provider.get("file").getAsString()));
            final int rows = provider.getAsJsonArray("chars").size();
            final int columns = provider.getAsJsonArray("chars").get(0).getAsString()
                    .codePointCount(0, provider.getAsJsonArray("chars").get(0).getAsString().length());
            final int cellWidth = image.getWidth() / columns;
            final int cellHeight = image.getHeight() / rows;
            final double scale = provider.get("height").getAsDouble() / cellHeight;

            for (int row = 0; row < rows; row++) {
                final int[] codePoints = provider.getAsJsonArray("chars").get(row).getAsString()
                        .codePoints().toArray();
                for (int column = 0; column < codePoints.length; column++) {
                    if (codePoints[column] == 0 || table.containsKey(codePoints[column])) {
                        continue;
                    }
                    final int rightmost = rightmost(image, column * cellWidth, row * cellHeight,
                            cellWidth, cellHeight);
                    table.put(codePoints[column], (int) (0.5 + (rightmost + 1) * scale) + 1);
                }
            }
        }
        return table;
    }

    /** The rightmost column of the cell with any alpha, relative to the cell, or -1. */
    private static int rightmost(final BufferedImage image, final int x0, final int y0,
                                 final int width, final int height) {
        for (int x = x0 + width - 1; x >= x0; x--) {
            for (int y = y0; y < y0 + height; y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return x - x0;
                }
            }
        }
        return -1;
    }

    private static BufferedImage read(final Path path) {
        try {
            final BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IllegalStateException(path + " is not an image ImageIO can read");
            }
            return image;
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
