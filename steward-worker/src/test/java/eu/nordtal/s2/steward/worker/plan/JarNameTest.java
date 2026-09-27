package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The filename rule, pinned against every jar this deployment actually runs.
 *
 * These six names come from the live APIs and {@code compose.yml}. The test is here so that a source which starts
 * publishing a differently shaped name breaks a build rather than a server: the failure mode of a mis-read prefix is
 * Paper loading two versions of the same plugin, which it does silently.
 */
class JarNameTest {

    @Nested
    class TheNamesThisDeploymentReallyRuns {

        @ParameterizedTest(name = "{0} -> {1} / {2}")
        @CsvSource({
            "smp-0.2.0.jar,                  smp,                  0.2.0",
            "limbo-0.2.0.jar,                limbo,                0.2.0",
            "hunger-games-0.2.0.jar,         hunger-games,         0.2.0",
            "proxy-0.2.0.jar,      proxy,      0.2.0",
            "discord-bot-0.2.0.jar,          discord-bot,          0.2.0",
            "papermc-display-tags-2.0.0.jar, papermc-display-tags, 2.0.0",
            "packetevents-spigot-2.13.0.jar, packetevents-spigot,  2.13.0",
            "voicechat-bukkit-2.6.24.jar,    voicechat-bukkit,     2.6.24",
            "paper-26.2-121.jar,             paper-26.2,           121",
            "velocity-4.1.1-24.jar,          velocity-4.1.1,       24",
        })
        void splitIntoPrefixAndVersion(final String fileName, final String prefix, final String version) {
            assertEquals(prefix, JarName.prefixOf(fileName));
            assertEquals(version, JarName.versionOf(fileName));
        }

        @Test
        void twoVersionsOfOnePluginShareAPrefixWhichIsWhatMakesOneSupersedeTheOther() {
            assertTrue(JarName.looksSuperseded("smp-0.1.0.jar", "smp-0.2.0.jar"));
            assertFalse(JarName.looksSuperseded("smp-0.2.0.jar", "smp-0.2.0.jar"));
            assertFalse(JarName.looksSuperseded("limbo-0.1.0.jar", "smp-0.2.0.jar"));
        }

        @Test
        void aPaperBuildSupersedesOnlyABuildOfTheSameMinecraftVersion() {
            // paper-26.2-120.jar supersedes -121.jar by their shared prefix; paper-26.1-* does not, by design.
            assertTrue(JarName.looksSuperseded("paper-26.2-120.jar", "paper-26.2-121.jar"));
            assertFalse(JarName.looksSuperseded("paper-26.1-99.jar", "paper-26.2-121.jar"));
        }
    }

    @Nested
    class TheShapesTheRuleCannotRead {

        @Test
        void aVersionQualifierMovesTheSplitWhichIsTheDocumentedGap() {
            // Nothing publishes such a name today; if it ever does, the new jar installs next to the one it replaces.
            assertEquals("packetevents-spigot-2.14.0", JarName.prefixOf("packetevents-spigot-2.14.0-SNAPSHOT.jar"));
            assertFalse(JarName.looksSuperseded(
                    "packetevents-spigot-2.13.0.jar", "packetevents-spigot-2.14.0-SNAPSHOT.jar"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"server.jar", "-1.0.jar", "plugins", "smp-0.2.0.jar.partial", "smp-.jar"})
        void anythingWithoutAReadableVersionHasNoPrefixAndNoVersion(final String fileName) {
            assertNull(JarName.prefixOf(fileName));
            assertNull(JarName.versionOf(fileName));
        }

        @Test
        void aPartialDownloadIsNotAJar() {
            // entrypoint.sh downloads to <name>.partial first; that must not count as an installed, finished jar.
            assertFalse(JarName.isJar("smp-0.2.0.jar.partial"));
            assertTrue(JarName.isJar("smp-0.2.0.jar"));
        }
    }
}
