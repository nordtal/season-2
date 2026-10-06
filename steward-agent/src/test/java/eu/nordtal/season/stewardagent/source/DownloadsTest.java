package eu.nordtal.season.stewardagent.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.nordtal.season.common.http.WebClient;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A release jar on its way from GitHub to disk, held against the digest the release names for it. */
class DownloadsTest {

    private static final byte[] JAR = "the smp jar as GitHub serves it".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path staging;

    private HttpServer server;

    @BeforeEach
    void serveTheJar() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/smp-0.1.0.jar", exchange -> {
            exchange.sendResponseHeaders(200, JAR.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(JAR);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aJarThatMatchesItsGitHubDigestIsKept() throws IOException, NoSuchAlgorithmException {
        final Path destination = staging.resolve("smp-0.1.0.jar");

        downloads().fetch(smpPublishedWith("sha256:" + sha256(JAR)), destination);

        assertArrayEquals(JAR, Files.readAllBytes(destination));
    }

    @Test
    void aJarThatDiffersFromItsGitHubDigestIsDeletedAndRefused() throws IOException, NoSuchAlgorithmException {
        final Path destination = staging.resolve("smp-0.1.0.jar");
        final RemoteFile file = smpPublishedWith("sha256:" + sha256("another build".getBytes(StandardCharsets.UTF_8)));

        final IOException refused =
                assertThrows(IOException.class, () -> downloads().fetch(file, destination));

        assertTrue(String.valueOf(refused.getMessage()).contains("Refusing to install it"), refused.getMessage());
        assertFalse(Files.exists(destination), "a jar that failed its digest is not left in staging");
    }

    /** The smp asset of the recorded release, pointed at this server and carrying {@code digest}. */
    private RemoteFile smpPublishedWith(final String digest) throws IOException {
        final String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/smp-0.1.0.jar";
        final String payload = FakeHttp.read("github-season-v0.1.0.json")
                .replace("https://github.com/nordtal/season-2/releases/download/v0.1.0/smp-0.1.0.jar", url)
                .replace("sha256:0f1ab0e5be10515e0e25a97f9818bba46b3e8a5f269e1534129c20b698ab1937", digest);
        final GitHubReleases.Asset asset = new GitHubReleases(new FakeHttp().answering("/releases/latest", payload))
                .latest("nordtal/season-2")
                .asset("smp-0.1.0.jar");
        assertNotNull(asset);
        return new RemoteFile("smp", "0.1.0", asset.name(), asset.url(), asset.digest());
    }

    private static Downloads downloads() {
        return new Downloads(WebClient.create(Duration.ofSeconds(5)));
    }

    private static String sha256(final byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
