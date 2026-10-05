package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.stewardagent.source.Checksum;
import eu.nordtal.season.stewardagent.source.Fetcher;
import eu.nordtal.season.stewardagent.source.Http;
import eu.nordtal.season.stewardagent.source.RemoteFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * A version's client jar from Mojang's launcher metadata, verified against its sha1 and kept only while it is drawn.
 *
 * Nothing of it is stored: the icons drawn from it go into the database, the jar into a temporary file.
 */
final class MojangClient implements ClientJars {

    static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    private final Http http;
    private final Fetcher fetcher;
    private final Path scratch;

    MojangClient(final Http http, final Fetcher fetcher, final Path scratch) {
        this.http = http;
        this.fetcher = fetcher;
        this.scratch = scratch;
    }

    @Override
    public Jar open(final String minecraftVersion) throws IOException {
        final RemoteFile client = client(minecraftVersion);
        Files.createDirectories(scratch);
        final Path jar = Files.createTempFile(scratch, "client-" + minecraftVersion + "-", ".jar");
        try {
            fetcher.fetch(client, jar);
            return new ZipJar(new ZipFile(jar.toFile()), jar);
        } catch (final IOException | RuntimeException failed) {
            Files.deleteIfExists(jar);
            throw failed;
        }
    }

    /** The client jar a version's metadata names, with its sha1. */
    RemoteFile client(final String minecraftVersion) throws IOException {
        final JsonObject manifest = parse(http.get(MANIFEST), MANIFEST);
        URI metadata = null;
        if (manifest.get("versions") instanceof JsonArray versions) {
            for (final JsonElement version : versions) {
                final String url = version instanceof JsonObject entry && minecraftVersion.equals(text(entry, "id"))
                        ? text(entry, "url")
                        : null;
                if (url != null) {
                    metadata = URI.create(url);
                }
            }
        }
        if (metadata == null) {
            throw new IOException("Mojang's version manifest lists no " + minecraftVersion);
        }
        final JsonObject version = parse(http.get(metadata), metadata);
        final JsonObject client = version.get("downloads") instanceof JsonObject downloads
                        && downloads.get("client") instanceof JsonObject declared
                ? declared
                : new JsonObject();
        final String url = text(client, "url");
        final String sha1 = text(client, "sha1");
        if (url == null || sha1 == null) {
            throw new IOException(minecraftVersion + "'s metadata names no client jar");
        }
        return new RemoteFile("minecraft-client", minecraftVersion, "client.jar", URI.create(url), Checksum.sha1(sha1));
    }

    private static JsonObject parse(final String body, final URI from) throws IOException {
        try {
            return Json.decode(body, JsonObject.class);
        } catch (final JsonParseException broken) {
            throw new IOException(from + " answered something that is not a JSON object", broken);
        }
    }

    private static @Nullable String text(final JsonObject object, final String member) {
        return object.get(member) instanceof JsonPrimitive value ? value.getAsString() : null;
    }

    /** The jar on disk, read entry by entry and deleted on close. */
    private record ZipJar(ZipFile zip, Path file) implements Jar {

        @Override
        public byte @Nullable [] bytes(final String path) {
            final ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (final IOException unreadable) {
                return null;
            }
        }

        @Override
        public void close() {
            try {
                zip.close();
                Files.deleteIfExists(file);
            } catch (final IOException stuck) {
                throw new UncheckedIOException(stuck);
            }
        }
    }
}
