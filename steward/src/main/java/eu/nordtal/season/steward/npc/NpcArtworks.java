package eu.nordtal.season.steward.npc;

import eu.nordtal.season.database.npc.NpcArtwork;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Reads uploaded portrait pictures: square PNGs that the pack build averages down to the portrait's 40 pixels. */
public final class NpcArtworks {

    /** The largest upload, in bytes. */
    static final int LIMIT = 1024 * 1024;

    /** The smallest side: the portrait's picture, which a smaller image could only fill by repeating pixels. */
    public static final int SMALLEST = 40;

    /** The largest side, so a decoded upload stays small. */
    public static final int LARGEST = 1024;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private NpcArtworks() {}

    /** Checks an uploaded PNG and returns it as a new artwork; refuses anything but a square PNG of 40 to 1024. */
    public static NpcArtwork uploaded(final byte[] bytes) {
        if (bytes.length > LIMIT
                || bytes.length < PNG.length
                || !Arrays.equals(PNG, Arrays.copyOf(bytes, PNG.length))) {
            throw new NotAnArtwork("not a PNG of at most 1 MiB");
        }
        checkSize(bytes);
        return new NpcArtwork(UUID.randomUUID(), Base64.getEncoder().encodeToString(bytes));
    }

    /** Reads only the PNG's header first, so a small file that names a huge image is refused before it is decoded. */
    private static void checkSize(final byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new NotAnArtwork("an unreadable PNG");
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                final int width = reader.getWidth(0);
                if (width != reader.getHeight(0) || width < SMALLEST || width > LARGEST) {
                    throw new NotAnArtwork("not square, or not 40 to 1024 pixels wide");
                }
                if (reader.read(0) == null) {
                    throw new NotAnArtwork("an unreadable PNG");
                }
            } finally {
                reader.dispose();
            }
        } catch (final IOException unreadable) {
            throw new NotAnArtwork("an unreadable PNG");
        }
    }

    /** An upload that is not a square PNG of {@value #SMALLEST} to {@value #LARGEST} pixels and at most 1 MiB. */
    public static final class NotAnArtwork extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public NotAnArtwork(final String why) {
            super(why);
        }
    }
}
