package eu.nordtal.s2.updater.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filename rule, pinned against every jar this deployment actually runs.
 * <p>
 * These six names were read off the live APIs and {@code compose.yml} on 2026-09-01. The test is
 * here so that a source which starts publishing a differently shaped name breaks a build rather
 * than a server: the failure mode of a mis-read prefix is Paper loading two versions of the same
 * plugin, which it does silently.
 * </p>
 */
class JarNameTest {

    @Nested
    @DisplayName("the names this deployment really runs")
    class RealNames {

        @ParameterizedTest(name = "{0} -> {1} / {2}")
        @CsvSource({
                "smp-0.2.0.jar,                  smp,                  0.2.0",
                "limbo-0.2.0.jar,                limbo,                0.2.0",
                "hunger-games-0.2.0.jar,         hunger-games,         0.2.0",
                "network-control-0.2.0.jar,      network-control,      0.2.0",
                "discord-bot-0.2.0.jar,          discord-bot,          0.2.0",
                "papermc-display-tags-2.0.0.jar, papermc-display-tags, 2.0.0",
                "packetevents-spigot-2.13.0.jar, packetevents-spigot,  2.13.0",
                "Chunky-Bukkit-1.5.3.jar,        Chunky-Bukkit,        1.5.3",
                "paper-26.2-121.jar,             paper-26.2,           121",
                "velocity-4.1.1-24.jar,          velocity-4.1.1,       24",
        })
        void splitIntoPrefixAndVersion(final String fileName, final String prefix, final String version) {
            assertEquals(prefix, JarName.prefixOf(fileName));
            assertEquals(version, JarName.versionOf(fileName));
        }

        @Test
        @DisplayName("two versions of one plugin share a prefix, which is what makes one supersede the other")
        void supersedes() {
            assertTrue(JarName.looksSuperseded("smp-0.1.0.jar", "smp-0.2.0.jar"));
            assertFalse(JarName.looksSuperseded("smp-0.2.0.jar", "smp-0.2.0.jar"));
            assertFalse(JarName.looksSuperseded("limbo-0.1.0.jar", "smp-0.2.0.jar"));
        }

        @Test
        @DisplayName("a Paper build supersedes only a build of the same Minecraft version")
        void serverJarsAreScopedToTheirVersion() {
            // paper-26.2-120.jar and paper-26.2-121.jar share the prefix 'paper-26.2', so the
            // older build is superseded. paper-26.1-* does not, which is right: a version change
            // is a season decision and its jar must not be deleted by a build bump.
            assertTrue(JarName.looksSuperseded("paper-26.2-120.jar", "paper-26.2-121.jar"));
            assertFalse(JarName.looksSuperseded("paper-26.1-99.jar", "paper-26.2-121.jar"));
        }
    }

    @Nested
    @DisplayName("the shapes the rule cannot read")
    class Limits {

        @Test
        @DisplayName("a version qualifier moves the split, which is the documented gap")
        void qualifierBreaksTheRule() {
            // Nothing in this deployment publishes such a name today. If one ever does, this is
            // where it is noticed: the prefix swallows the version, so the new jar would be
            // installed NEXT TO the one it replaces rather than over it.
            assertEquals("packetevents-spigot-2.14.0",
                    JarName.prefixOf("packetevents-spigot-2.14.0-SNAPSHOT.jar"));
            assertFalse(JarName.looksSuperseded(
                    "packetevents-spigot-2.13.0.jar", "packetevents-spigot-2.14.0-SNAPSHOT.jar"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"server.jar", "-1.0.jar", "plugins", "smp-0.2.0.jar.partial", "smp-.jar"})
        @DisplayName("anything without a readable version has no prefix and no version")
        void unreadable(final String fileName) {
            assertNull(JarName.prefixOf(fileName));
            assertNull(JarName.versionOf(fileName));
        }

        @Test
        @DisplayName("a .partial download is not a jar")
        void partialsAreNotJars() {
            // entrypoint.sh and step 3 of this module both download to <name>.partial first. A
            // scan that counted those as installed jars would report an interrupted download as a
            // finished install.
            assertFalse(JarName.isJar("smp-0.2.0.jar.partial"));
            assertTrue(JarName.isJar("smp-0.2.0.jar"));
        }
    }
}
