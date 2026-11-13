package eu.nordtal.season.packbuilder.wheel;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** The textures the pack draws for the wheel, from vanilla's letters and the kit's colours. */
final class WheelArt {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF3F3F3F;
    private static final int DISABLED = 0xFFBDBDBD;
    private static final int FAINT = 0xFF8B8B8B;

    private WheelArt() {}

    static BufferedImage clear() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    /** Digit {@code d} as a stack count: white with vanilla's shadow one pixel right and down, 6 x 8. */
    static BufferedImage digit(final BufferedImage ascii, final int d) {
        final boolean[][] rows = WheelVanilla.glyph(ascii, (char) ('0' + d)).rows();
        final BufferedImage image = new BufferedImage(6, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 5; x++) {
                if (rows[y][x]) {
                    image.setRGB(x + 1, y + 1, SHADOW);
                }
            }
        }
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 5; x++) {
                if (rows[y][x]) {
                    image.setRGB(x, y, WHITE);
                }
            }
        }
        return image;
    }

    /** The placeholder for a prize the pack does not list: a white question mark at twice its size, with its shadow. */
    static BufferedImage unknown(final BufferedImage ascii) {
        final boolean[][] rows = WheelVanilla.glyph(ascii, '?').rows();
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        draw(image, rows, SHADOW, 2);
        draw(image, rows, WHITE, 0);
        return image;
    }

    private static void draw(final BufferedImage image, final boolean[][] rows, final int colour, final int shift) {
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 5; x++) {
                if (!rows[y][x]) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dx = 0; dx <= 1; dx++) {
                        image.setRGB(3 + 2 * x + dx + shift, 2 * y + dy + shift, colour);
                    }
                }
            }
        }
    }

    /** The disabled plate with {@code label} centred in faint letters, its capitals from row 5, as a plate draws it. */
    static BufferedImage offPlate(final BufferedImage ascii, final String label) {
        final BufferedImage image =
                new BufferedImage(WheelLayout.PLATE_WIDTH, WheelLayout.PLATE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, DISABLED);
            }
        }
        final List<WheelVanilla.Glyph> glyphs = new ArrayList<>();
        int width = -1;
        for (int i = 0; i < label.length(); i++) {
            final WheelVanilla.Glyph glyph = WheelVanilla.glyph(ascii, label.charAt(i));
            glyphs.add(glyph);
            width += glyph.advance();
        }
        int pen = (image.getWidth() - width) / 2;
        for (final WheelVanilla.Glyph glyph : glyphs) {
            final boolean[][] rows = glyph.rows();
            for (int y = 0; y < rows.length; y++) {
                for (int x = 0; x < rows[y].length; x++) {
                    if (rows[y][x] && pen + x < image.getWidth() && 5 + y < image.getHeight()) {
                        image.setRGB(pen + x, 5 + y, FAINT);
                    }
                }
            }
            pen += glyph.advance();
        }
        return image;
    }

    static byte[] png(final BufferedImage image) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
