package eu.nordtal.s2.steward.worker.http;

import eu.nordtal.s2.steward.worker.source.Checksum;
import eu.nordtal.s2.steward.worker.source.RemoteFile;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Fetches a jar straight to disk and verifies it.
 *
 * Modrinth and Fill checksums are checked; GitHub release assets carry none and arrive unverified.
 */
public final class Downloads implements Fetcher {

    private final HttpClient client;
    private final Duration timeout;

    public Downloads(final Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build();
    }

    /**
     * Downloads {@code file} to {@code destination} in the caller's staging directory, verifying any checksum.
     *
     * @throws IOException on a transport failure, a non-2xx status, or a checksum that disagrees
     */
    @Override
    public void fetch(final RemoteFile file, final Path destination) throws IOException {
        final HttpRequest request = HttpRequest.newBuilder(file.url())
                .GET()
                .timeout(timeout)
                .header("User-Agent", JdkHttp.USER_AGENT)
                .build();

        final HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading " + file.fileName(), interrupted);
        }

        if (response.statusCode() / 100 != 2) {
            throw new HttpException(file.url(), response.statusCode(), "");
        }

        Files.createDirectories(destination.getParent());
        try (InputStream body = response.body()) {
            Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
        }

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
