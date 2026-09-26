package eu.nordtal.s2.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link Platform} and {@code gradle/libs.versions.toml} and the other mirrors to the same versions.
 *
 * The catalog is what modules compile against and {@link Platform} is what the worker installs. The mirrors
 * are declared through {@code repositoryRootTestInputs}, without which this test would stay up to date.
 */
class PlatformTest {

    private static String catalog;

    @BeforeAll
    static void read() throws IOException {
        catalog = Files.readString(repositoryRoot().resolve("gradle/libs.versions.toml"), StandardCharsets.UTF_8);
    }

    @Test
    void minecraftIsTheVersionThePaperApiCoordinateIsBuiltFrom() {
        // Only the part in front of `.build.` is a Minecraft version, and the only part Fill and Modrinth understand.
        final String paper = version("paper");
        final int build = paper.indexOf(".build.");
        assertTrue(
                build > 0,
                "the paper coordinate no longer carries `.build.`, so this test can no"
                        + " longer say which Minecraft version it names: " + paper);

        assertEquals(
                paper.substring(0, build),
                Platform.MINECRAFT,
                "Platform.MINECRAFT and the paper-api version in gradle/libs.versions.toml name"
                        + " different Minecraft versions. One of them decides what the worker"
                        + " installs and the other decides what every plugin is compiled against;"
                        + " a network where they disagree loads no plugins.");
    }

    @Test
    void velocityApiIsTheVelocityApiTheProxyIsCompiledAgainst() {
        // The update report compares against this, so it must be the version the proxy plugin is built with.
        assertEquals(
                version("velocity"),
                Platform.VELOCITY_API,
                "Platform.VELOCITY_API no longer matches the catalog. The worker's warning about"
                        + " running the proxy on a newer API than it was built for is measured"
                        + " against this string, so a stale one makes that warning meaningless.");
    }

    @Test
    void theCatalogsVelocityFallsInsideTheFamilyStewardWorkerFollows() {
        // VELOCITY_FAMILY names a major line, which must be the line the proxy is compiled for.
        assertEquals(
                major(Platform.VELOCITY_API),
                major(Platform.VELOCITY_FAMILY),
                "Platform.VELOCITY_FAMILY (" + Platform.VELOCITY_FAMILY + ") and the velocity-api in"
                        + " the catalog (" + Platform.VELOCITY_API + ") are different majors. The"
                        + " worker would install a proxy build the plugin cannot run on.");
    }

    private static String major(final String version) {
        final int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }

    /** One {@code name = "value"} out of the catalog's {@code [versions]} table. */
    @Test
    void packFormatIsTheNumberTheShippedPackMcmetaCarries() throws IOException {
        // A client accepts an older pack format with only a warning, so drift would go unnoticed.
        final String mcmeta =
                Files.readString(repositoryRoot().resolve("resource-pack/src/pack.mcmeta"), StandardCharsets.UTF_8);
        final Matcher format = Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)").matcher(mcmeta);
        assertTrue(format.find(), "resource-pack/src/pack.mcmeta declares no pack_format");
        assertEquals(
                Platform.PACK_FORMAT,
                Integer.parseInt(format.group(1)),
                "Platform.PACK_FORMAT and resource-pack/src/pack.mcmeta name different pack"
                        + " formats. The pack is chosen for a Minecraft version and this is the"
                        + " number that says which one.");
    }

    @Test
    void allThreePaperDescriptorsDeclareApiVersionAndItIsMinecraft() throws IOException {
        // Paper accepts an older api-version silently and applies its compatibility behaviour.
        for (final String module : new String[] {"smp", "limbo", "hunger-games"}) {
            final Path descriptor = repositoryRoot().resolve(module + "/src/main/resources/paper-plugin.yml");
            final Matcher declared = Pattern.compile("(?m)^api-version:\\s*[\"']?([^\"'\\s]+)[\"']?$")
                    .matcher(Files.readString(descriptor, StandardCharsets.UTF_8));
            assertTrue(declared.find(), module + "'s paper-plugin.yml declares no api-version");
            assertEquals(
                    Platform.API_VERSION,
                    declared.group(1),
                    module + "'s paper-plugin.yml declares an api-version that is not" + " Platform.API_VERSION");
        }

        assertEquals(
                Platform.MINECRAFT,
                Platform.API_VERSION,
                "API_VERSION and MINECRAFT have parted company. That is allowed - a season may"
                        + " deliberately stay compatible with an older API - but it is a decision,"
                        + " so say so here rather than letting the two drift apart by accident.");
    }

    private static String version(final String key) {
        final Matcher matcher =
                Pattern.compile("(?m)^" + key + "\\s*=\\s*\"([^\"]+)\"").matcher(catalog);
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
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
