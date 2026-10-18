package eu.nordtal.season.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of a text. */
public final class Sha256 {

    private Sha256() {}

    /** Returns the SHA-256 of the UTF-8 bytes of {@code text} as 64 lower-case hex digits. */
    public static String hex(final String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }
}
