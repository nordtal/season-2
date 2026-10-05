package eu.nordtal.season.stewardagent.gamedata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.stewardagent.source.Checksum;
import eu.nordtal.season.stewardagent.source.RemoteFile;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The client jar found through Mojang's launcher metadata, and deleted again once drawn. */
class MojangClientTest {

    private static final URI VERSION = URI.create("https://piston-meta.mojang.com/v1/packages/abc/26.2.json");
    private static final URI JAR = URI.create("https://piston-data.mojang.com/v1/objects/def/client.jar");

    private static final Map<URI, String> MOJANG = Map.of(
            MojangClient.MANIFEST,
            "{\"versions\": [{\"id\": \"26.3\", \"url\": \"https://example.invalid/26.3.json\"},"
                    + " {\"id\": \"26.2\", \"url\": \"" + VERSION + "\"}]}",
            VERSION,
            "{\"downloads\": {\"client\": {\"sha1\": \"DEF0\", \"size\": 39193383, \"url\": \"" + JAR + "\"}}}");

    @TempDir
    Path scratch;

    @Test
    void theVersionsClientJarIsNamedWithItsSha1() throws IOException {
        final RemoteFile client = client((file, into) -> {}).client("26.2");

        assertEquals(JAR, client.url());
        assertEquals(Checksum.sha1("def0"), client.checksum());
    }

    @Test
    void aVersionMojangDoesNotListFailsTheFetch() {
        assertThrows(IOException.class, () -> client((file, into) -> {}).client("1.0-unreleased"));
    }

    @Test
    void theJarIsReadWhileOpenAndGoneOnceClosed() throws IOException {
        final MojangClient mojang = client((file, into) -> {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(into))) {
                jar.putNextEntry(new JarEntry("assets/minecraft/items/stone.json"));
                jar.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });

        try (ClientJars.Jar jar = mojang.open("26.2")) {
            assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), jar.bytes("assets/minecraft/items/stone.json"));
            assertEquals(1, files());
        }
        assertEquals(0, files());
    }

    @Test
    void aFailedDownloadLeavesNothingBehind() throws IOException {
        final MojangClient mojang = client((file, into) -> {
            Files.writeString(into, "half");
            throw new IOException("does not match its published sha1");
        });

        assertThrows(IOException.class, () -> mojang.open("26.2"));
        assertEquals(0, files());
    }

    private MojangClient client(final eu.nordtal.season.stewardagent.source.Fetcher fetcher) {
        return new MojangClient(MOJANG::get, fetcher, scratch);
    }

    private long files() throws IOException {
        try (var listing = Files.list(scratch)) {
            return listing.count();
        }
    }
}
