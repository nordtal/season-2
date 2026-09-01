package eu.nordtal.s2.updater.source;

import eu.nordtal.s2.updater.http.FakeHttp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two Modrinth traps, against the payloads the live API returned on 2026-09-01.
 */
class ModrinthTest {

    private static final String MC = "26.2";

    @Test
    @DisplayName("PacketEvents: the primary file is taken and the -sources.jar in the same version is not")
    void skipsTheSourcesJar() throws IOException {
        // The single most valuable assertion in this module. PacketEvents publishes two files under
        // one version; matching on '.jar' alone puts source code in a plugins folder, where it
        // loads as a plugin with no code in it and DisplayTags fails to find its packet library.
        final Modrinth modrinth = new Modrinth(
                new FakeHttp().serving("/project/HYKaKraK/version", "modrinth-packetevents.json"));

        final RemoteFile file = modrinth.newest("packetevents", "HYKaKraK", MC, "paper");

        assertEquals("packetevents-spigot-2.13.0.jar", file.fileName());
        assertEquals("2.13.0+spigot", file.version(), "the version string is not the filename's");
        assertNotNull(file.checksum());
        assertEquals("sha512", file.checksum().algorithm());
    }

    @Test
    @DisplayName("Chunky: filename and version differ, and the filename is what is used")
    void usesThePublishedFilename() throws IOException {
        final Modrinth modrinth = new Modrinth(
                new FakeHttp().serving("/project/fALzjamp/version", "modrinth-chunky.json"));

        final RemoteFile file = modrinth.newest("chunky", "fALzjamp", MC, "paper");

        assertEquals("Chunky-Bukkit-1.5.3.jar", file.fileName());
        assertEquals("1.5.3", file.version());
    }

    @Test
    @DisplayName("the game_versions and loaders filters are sent as Modrinth's bracketed JSON")
    void sendsTheDocumentedFilters() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/project/fALzjamp/version", "modrinth-chunky.json");
        new Modrinth(http).newest("chunky", "fALzjamp", MC, "paper");

        // Percent-encoded, because the brackets and quotes are data inside a query parameter. If
        // they are ever sent raw the API answers with every version of the project for every
        // Minecraft version, and the newest of those is not one that runs here.
        final String url = http.requested().getFirst().toString();
        assertTrue(url.contains("game_versions=%5B%2226.2%22%5D"), url);
        assertTrue(url.contains("loaders=%5B%22paper%22%5D"), url);
    }

    @Test
    @DisplayName("an empty result is refused, not worked around")
    void refusesWhenNothingMatchesThePlatform() {
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", "[]"));

        final IOException failure = assertThrows(IOException.class,
                () -> modrinth.newest("chunky", "fALzjamp", "27.0", "paper"));

        // "The plugin has no 26.2 build yet" must not become "install the 26.1 build instead".
        assertTrue(failure.getMessage().contains("no stable release"), failure.getMessage());
    }

    @Test
    @DisplayName("betas and alphas are not releases")
    void ignoresPreReleases() {
        final String body = """
                [{"version_number":"1.6.0","version_type":"beta","date_published":"2026-08-01T00:00:00Z",
                  "files":[{"filename":"Chunky-Bukkit-1.6.0.jar","primary":true,
                            "url":"https://cdn.modrinth.com/x","hashes":{"sha512":"ab"}}]}]
                """;
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", body));

        assertThrows(IOException.class, () -> modrinth.newest("chunky", "fALzjamp", MC, "paper"));
    }

    @Test
    @DisplayName("the newest release wins even when the API returns them oldest-first")
    void sortsByPublicationDate() throws IOException {
        // The list comes back newest-first in practice and that is not documented anywhere. The
        // cost of relying on it would be installing a two-year-old build without a word.
        final String body = """
                [{"version_number":"1.5.0","version_type":"release","date_published":"2024-01-01T00:00:00Z",
                  "files":[{"filename":"Chunky-Bukkit-1.5.0.jar","primary":true,
                            "url":"https://cdn.modrinth.com/old","hashes":{"sha512":"aa"}}]},
                 {"version_number":"1.5.3","version_type":"release","date_published":"2026-05-04T05:46:08Z",
                  "files":[{"filename":"Chunky-Bukkit-1.5.3.jar","primary":true,
                            "url":"https://cdn.modrinth.com/new","hashes":{"sha512":"bb"}}]}]
                """;
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", body));

        assertEquals("Chunky-Bukkit-1.5.3.jar", modrinth.newest("chunky", "fALzjamp", MC, "paper").fileName());
    }

    @Test
    @DisplayName("a version with no primary file is refused rather than guessed at")
    void refusesAVersionWithNoPrimaryFile() {
        final String body = """
                [{"version_number":"9.9.9","version_type":"release","date_published":"2026-09-01T00:00:00Z",
                  "files":[{"filename":"thing-9.9.9-sources.jar","primary":false,
                            "url":"https://cdn.modrinth.com/s","hashes":{"sha512":"cc"}}]}]
                """;
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", body));

        final IOException failure = assertThrows(IOException.class,
                () -> modrinth.newest("packetevents", "HYKaKraK", MC, "paper"));
        assertTrue(failure.getMessage().contains("primary"), failure.getMessage());
    }
}
