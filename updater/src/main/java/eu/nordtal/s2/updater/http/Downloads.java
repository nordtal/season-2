package eu.nordtal.s2.updater.http;

import eu.nordtal.s2.updater.source.Checksum;
import eu.nordtal.s2.updater.source.RemoteFile;

import org.jetbrains.annotations.NotNull;

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
 * Fetching a jar onto disk and proving it is the jar that was asked for.
 *
 * <h2>Separate from {@link Http} on purpose</h2>
 * {@link Http} returns a {@code String} and is faked in tests with recorded payloads, which is
 * right for six small JSON documents and wrong for a 64 MB Paper jar. This writes straight to a
 * file and never holds the body in memory.
 *
 * <h2>What is verified and what is not</h2>
 * Modrinth publishes a sha512 per file and the Fill API a sha256 per build; both are checked, and a
 * mismatch deletes the download rather than moving it anywhere. <b>A GitHub release asset carries
 * no digest of any kind</b> (checked against the live API 2026-09-01), so our own six jars, the
 * pack and the DisplayTags jar arrive unverified - exactly as {@code entrypoint.sh} has always
 * fetched them. The mitigation is TLS to {@code github.com} and the fact that a truncated jar is
 * one the JVM refuses to load, loudly, at start. It is a real gap and it is written down rather
 * than implied.
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
     * Downloads {@code file} to {@code destination}, verifying its checksum if it has one.
     * <p>
     * The destination is written directly rather than through a {@code .partial} rename, because
     * every caller in this module already downloads into a staging directory it is about to throw
     * away - see {@code Applier}. A failure therefore leaves a file nobody will move.
     * </p>
     *
     * @throws IOException on a transport failure, a non-2xx status, or a checksum that disagrees.
     */
    @Override
    public void fetch(final @NotNull RemoteFile file, final @NotNull Path destination) throws IOException {
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

    /** Hex digest of a file, streamed - these are jars, not strings. */
    public static @NotNull String digest(final @NotNull Path file, final @NotNull String algorithm)
            throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(switch (algorithm) {
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
