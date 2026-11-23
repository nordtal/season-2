package eu.nordtal.season.packbuilder.png;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** Encodes images as PNG. The same pixels always give the same bytes, since the encoder writes no time. */
public final class Png {

    private Png() {}

    /** Returns {@code image} as PNG bytes. */
    public static byte[] png(final BufferedImage image) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
