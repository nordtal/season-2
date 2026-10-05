package eu.nordtal.season.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of text, the one place that asks the JVM for it. */
public final class Sha256 {

    private Sha256() {}

    /**
     * The SHA-256 of {@code text}'s UTF-8 bytes.
     *
     * @return 64 lower-case hex digits
     */
    public static String hex(final String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }
}
