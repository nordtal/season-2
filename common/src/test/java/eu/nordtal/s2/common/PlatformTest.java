package eu.nordtal.s2.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Platform} and {@code gradle/libs.versions.toml} are two copies of one fact, and this is
 * what makes the second one fail loudly.
 *
 * <h2>What each half is for</h2>
 * The catalog is what the modules <em>compile</em> against; {@link Platform} is what the updater
 * <em>installs</em> and what {@code compose.yml} names. Nothing else compares them, and the way
 * they part company is silent in both directions: a catalog bumped on its own gives a network
 * running one Minecraft version and plugins built for another, and a constant bumped on its own
 * gives an updater fetching a Paper the plugins were never compiled for. Either way the first
 * symptom is a plugin that does not load, on a server, in front of players.
 *
 * <p>The file is at the repository root and in no source set, so {@code common/build.gradle.kts}
 * declares it through {@code repositoryRootTestInputs}. Without that Gradle cannot see it, an edit
 * to the catalog leaves {@code :common:test} UP-TO-DATE, and the one check that would have caught
 * the drift is the one that does not run.</p>
 */
class PlatformTest {

    private static String catalog;

    @BeforeAll
    static void read() throws IOException {
        catalog = Files.readString(repositoryRoot().resolve("gradle/libs.versions.toml"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("MINECRAFT is the version the paper-api coordinate is built from")
    void theMinecraftVersionIsTheOneTheModulesCompileAgainst() {
        // The catalog spells it 26.2.build.121-stable: Paper dropped -R0.1-SNAPSHOT with the 26.x
        // scheme and puts the build in the coordinate instead. Only the part in front of `.build.`
        // is a Minecraft version - and it is the only part Fill and Modrinth understand.
        final String paper = version("paper");
        final int build = paper.indexOf(".build.");
        assertTrue(build > 0, "the paper coordinate no longer carries `.build.`, so this test can no"
                + " longer say which Minecraft version it names: " + paper);

        assertEquals(paper.substring(0, build), Platform.MINECRAFT,
                "Platform.MINECRAFT and the paper-api version in gradle/libs.versions.toml name"
                        + " different Minecraft versions. One of them decides what the updater"
                        + " installs and the other decides what every plugin is compiled against;"
                        + " a network where they disagree loads no plugins.");
    }

    @Test
    @DisplayName("VELOCITY_API is the velocity-api the proxy is compiled against")
    void theVelocityApiVersionIsTheOneTheProxyCompilesAgainst() {
        // This is the number the update report compares a resolved Velocity version against, and it
        // is only worth anything if it is the version network-control was actually built with.
        assertEquals(version("velocity"), Platform.VELOCITY_API,
                "Platform.VELOCITY_API no longer matches the catalog. The updater's warning about"
                        + " running the proxy on a newer API than it was built for is measured"
                        + " against this string, so a stale one makes that warning meaningless.");
    }

    @Test
    @DisplayName("the catalog's Velocity falls inside the family the updater follows")
    void theCatalogVersionIsAMemberOfTheFamily() {
        // VELOCITY_FAMILY is Fill's key for a major, not a version - `4.0.0` is what it calls the
        // whole 4.x line. What has to hold is that the line the updater follows is the line the
        // proxy is compiled for: following major 5 while compiling against 4.1.1 is not a warning
        // in a report, it is a proxy that does not start.
        assertEquals(major(Platform.VELOCITY_API), major(Platform.VELOCITY_FAMILY),
                "Platform.VELOCITY_FAMILY (" + Platform.VELOCITY_FAMILY + ") and the velocity-api in"
                        + " the catalog (" + Platform.VELOCITY_API + ") are different majors. The"
                        + " updater would install a proxy network-control cannot run on.");
    }

    private static String major(final String version) {
        final int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** One {@code name = "value"} out of the catalog's {@code [versions]} table. */
    private static String version(final String key) {
        final Matcher matcher = Pattern.compile("(?m)^" + key + "\\s*=\\s*\"([^\"]+)\"")
                .matcher(catalog);
        assertTrue(matcher.find(), "gradle/libs.versions.toml declares no version called '" + key + "'");
        final String value = matcher.group(1);
        assertNotNull(value);
        return value;
    }

    /** The directory holding {@code settings.gradle.kts}, not the nearest file by name. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
