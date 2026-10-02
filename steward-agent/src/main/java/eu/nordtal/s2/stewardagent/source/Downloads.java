package eu.nordtal.s2.stewardagent.source;

import eu.nordtal.s2.common.http.WebClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fetches a jar straight to disk and verifies it.
 *
 * Modrinth and Fill checksums are checked; GitHub release assets carry none and arrive unverified.
 */
public final class Downloads implements Fetcher {

    private final WebClient web;

    public Downloads(final WebClient web) {
        this.web = web;
    }

    /**
     * Downloads {@code file} to {@code destination} in the caller's staging directory, verifying any checksum.
     */
    @Override
    public void fetch(final RemoteFile file, final Path destination) throws IOException {
        web.download(file.url(), destination);

        final Checksum expected = file.checksum();
        if (expected == null) {
            return;
        }
        final String actual = digest(destination, expected.algorithm());
        if (!actual.equalsIgnoreCase(expected.hex())) {
            Files.deleteIfExists(destination);
            throw new IOException(file.fileName() + " does not match its published "
                    + expected.algorithm() + ": expected " + expected.hex() + ", got " + actual
                    + ". Refusing to install it.");
        }
    }

    /** Hex digest of a file, streamed. */
    public static String digest(final Path file, final String algorithm) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(
                    switch (algorithm) {
                        case "sha1" -> "SHA-1";
                        case "sha256" -> "SHA-256";
                        case "sha512" -> "SHA-512";
                        default -> throw new IOException("no digest known for '" + algorithm + "'");
                    });
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IOException("this JVM has no " + algorithm, impossible);
        }

        try (InputStream stream = Files.newInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
