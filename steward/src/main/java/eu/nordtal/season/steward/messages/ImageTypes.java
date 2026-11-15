package eu.nordtal.season.steward.messages;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** The media type of an uploaded image, read from its first bytes. */
final class ImageTypes {

    private ImageTypes() {}

    /** Returns the type of a PNG, JPEG, GIF or WebP image, empty for any other bytes. */
    static Optional<String> of(final byte[] bytes) {
        if (starts(bytes, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return Optional.of("image/png");
        }
        if (starts(bytes, 0, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return Optional.of("image/jpeg");
        }
        if (starts(bytes, 0, ascii("GIF87a")) || starts(bytes, 0, ascii("GIF89a"))) {
            return Optional.of("image/gif");
        }
        if (starts(bytes, 0, ascii("RIFF")) && starts(bytes, 8, ascii("WEBP"))) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static byte[] ascii(final String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean starts(final byte[] bytes, final int at, final byte[] prefix) {
        return bytes.length >= at + prefix.length
                && Arrays.equals(bytes, at, at + prefix.length, prefix, 0, prefix.length);
    }
}
