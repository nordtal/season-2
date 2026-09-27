package eu.nordtal.s2.steward.worker.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.http.FakeHttp;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two Modrinth traps, against the payloads the live API returns. */
class ModrinthTest {

    private static final String MC = "26.2";

    @Test
    void packeteventsThePrimaryFileIsTakenAndTheSourcesJarInTheSameVersionIsNot() throws IOException {
        // Matching on '.jar' alone would let source code load as a plugin, one with no code DisplayTags can find.
        final Modrinth modrinth =
                new Modrinth(new FakeHttp().serving("/project/HYKaKraK/version", "modrinth-packetevents.json"));

        final RemoteFile file = modrinth.newest("packetevents", "HYKaKraK", MC, "paper");

        assertEquals("packetevents-spigot-2.13.0.jar", file.fileName());
        assertEquals("2.13.0+spigot", file.version(), "the version string is not the filename's");
        assertNotNull(file.checksum());
        assertEquals("sha512", file.checksum().algorithm());
    }

    @Test
    void simpleVoiceChatFilenameAndVersionDifferAndTheFilenameIsWhatIsUsed() throws IOException {
        final Modrinth modrinth =
                new Modrinth(new FakeHttp().serving("/project/9eGKb6K1/version", "modrinth-voicechat.json"));

        final RemoteFile file = modrinth.newest("voicechat", "9eGKb6K1", MC, "paper");

        assertEquals("voicechat-bukkit-2.6.23.jar", file.fileName());
        assertEquals("bukkit-2.6.23", file.version());
    }

    @Test
    void theGameVersionsAndLoadersFiltersAreSentAsModrinthsBracketedJson() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/project/9eGKb6K1/version", "modrinth-voicechat.json");
        new Modrinth(http).newest("voicechat", "9eGKb6K1", MC, "paper");

        // Percent-encoded: raw brackets and quotes in a query parameter return every version for every MC version.
        final String url = http.requested().getFirst().toString();
        assertTrue(url.contains("game_versions=%5B%2226.2%22%5D"), url);
        assertTrue(url.contains("loaders=%5B%22paper%22%5D"), url);
    }

    @Test
    void anEmptyResultIsRefusedNotWorkedAround() {
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", "[]"));

        final IOException failure =
                assertThrows(IOException.class, () -> modrinth.newest("voicechat", "9eGKb6K1", "27.0", "paper"));

        // "The plugin has no 26.2 build yet" must not become "install the 26.1 build instead".
        assertTrue(failure.getMessage().contains("no stable release"), failure.getMessage());
    }

    @Test
    void betasAndAlphasAreNotReleases() {
        final String body = """
                [{"version_number":"1.6.0","version_type":"beta","date_published":"2026-08-01T00:00:00Z",
                  "files":[{"filename":"voicechat-bukkit-1.6.0.jar","primary":true,
                            "url":"https://cdn.modrinth.com/x","hashes":{"sha512":"ab"}}]}]
                """;
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", body));

        assertThrows(IOException.class, () -> modrinth.newest("voicechat", "9eGKb6K1", MC, "paper"));
    }

    /** Three velocity builds, all pre-releases, the shape the real project has. */
    private static final String VELOCITY_PRE_RELEASES = """
            [{"version_number":"velocity-2.6.4","version_type":"alpha","date_published":"2025-09-19T11:08:43Z",
              "files":[{"filename":"voicechat-velocity-2.6.4.jar","primary":true,
                        "url":"https://cdn.modrinth.com/old","hashes":{"sha512":"aa"}}]},
             {"version_number":"velocity-2.6.18","version_type":"alpha","date_published":"2026-05-28T08:40:05Z",
              "files":[{"filename":"voicechat-velocity-2.6.18.jar","primary":true,
                        "url":"https://cdn.modrinth.com/new","hashes":{"sha512":"bb"}}]},
             {"version_number":"velocity-2.5.31","version_type":"beta","date_published":"2025-06-23T15:24:38Z",
              "files":[{"filename":"voicechat-velocity-2.5.31.jar","primary":true,
                        "url":"https://cdn.modrinth.com/older","hashes":{"sha512":"cc"}}]}]
            """;

    @Test
    void voiceChatsProxyPluginIsResolvedFromAPreReleaseNewestFirst() throws IOException {
        // This project has never published a Velocity release; waiting for one means never installing the plugin.
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", VELOCITY_PRE_RELEASES));

        final RemoteFile file = modrinth.newest("voicechat-velocity", "9eGKb6K1", MC, "velocity");

        assertEquals("voicechat-velocity-2.6.18.jar", file.fileName());
        // Newest by date, not first in the list and not the highest version_type: the rule every artefact gets.
        assertEquals("velocity-2.6.18", file.version());
    }

    @Test
    void theExceptionIsOneArtefactIdAndNothingElseLeansOnIt() {
        // A named constant makes the case for a new entry 'a stable release has never existed', not 'one is available'.
        assertEquals(List.of("voicechat-velocity"), Modrinth.PRE_RELEASE_EXCEPTIONS);
        // The id lives here, not imported, so source keeps depending on nothing but http; a drift here is silent.
        assertEquals(
                List.of(eu.nordtal.s2.steward.worker.plan.Topology.VOICE_CHAT_PROXY),
                Modrinth.PRE_RELEASE_EXCEPTIONS,
                "the exception names an artefact the topology does not");

        // Every other artefact the resolver asks Modrinth for still refuses the same payload, id aside.
        for (final String artifact : List.of("packetevents", "voicechat", "coreprotect")) {
            final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", VELOCITY_PRE_RELEASES));

            final IOException refused = assertThrows(
                    Modrinth.Unsupported.class,
                    () -> modrinth.newest(artifact, "9eGKb6K1", MC, "velocity"),
                    artifact + " accepted a pre-release. The exception is meant to be one artefact,"
                            + " named, and this is the test that keeps it one.");
            assertTrue(refused.getMessage().contains("no stable release"), refused.getMessage());
        }
    }

    @Test
    void evenTheNamedExceptionRefusesAnEmptyResultRatherThanReachingFurtherBack() {
        // The exception widens which version_types count, never which Minecraft version counts.
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", "[]"));

        final IOException refused = assertThrows(
                Modrinth.Unsupported.class, () -> modrinth.newest("voicechat-velocity", "9eGKb6K1", MC, "velocity"));
        assertTrue(refused.getMessage().contains("no version of any kind"), refused.getMessage());
    }

    @Test
    void theNewestReleaseWinsEvenWhenTheApiReturnsThemOldestFirst() throws IOException {
        // The list comes back newest-first in practice, undocumented; relying on it risks a silent two-year-old build.
        final String body = """
                [{"version_number":"1.5.0","version_type":"release","date_published":"2024-01-01T00:00:00Z",
                  "files":[{"filename":"voicechat-bukkit-1.5.0.jar","primary":true,
                            "url":"https://cdn.modrinth.com/old","hashes":{"sha512":"aa"}}]},
                 {"version_number":"1.5.3","version_type":"release","date_published":"2026-05-04T05:46:08Z",
                  "files":[{"filename":"voicechat-bukkit-1.5.3.jar","primary":true,
                            "url":"https://cdn.modrinth.com/new","hashes":{"sha512":"bb"}}]}]
                """;
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", body));

        assertEquals(
                "voicechat-bukkit-1.5.3.jar",
                modrinth.newest("voicechat", "9eGKb6K1", MC, "paper").fileName());
    }

    @Test
    void aVersionWithNoPrimaryFileIsRefusedRatherThanGuessedAt() {
        final String body = """
                [{"version_number":"9.9.9","version_type":"release","date_published":"2026-09-01T00:00:00Z",
                  "files":[{"filename":"thing-9.9.9-sources.jar","primary":false,
                            "url":"https://cdn.modrinth.com/s","hashes":{"sha512":"cc"}}]}]
                """;
        final Modrinth modrinth = new Modrinth(new FakeHttp().answering("/version", body));

        final IOException failure =
                assertThrows(IOException.class, () -> modrinth.newest("packetevents", "HYKaKraK", MC, "paper"));
        assertTrue(failure.getMessage().contains("primary"), failure.getMessage());
    }
}
