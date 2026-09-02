package eu.nordtal.s2.smp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the world {@code compose.yml} tells Paper to generate is the world this plugin looks for.
 *
 * <h2>Why this file exists</h2>
 * Until 2026-09-02 nothing wrote {@code level-name} into {@code server.properties} at all.
 * {@code deploy/minecraft/entrypoint.sh} fetched Terralith and Dungeons and Taverns into
 * {@code /data/${LEVEL_NAME}/datapacks} - {@code nordtal}, per {@code compose.yml} - while Paper
 * kept its own default and generated {@code world}. The consequences were three, and the middle one
 * is the expensive one: the datapacks were never loaded, so the season world would have been
 * vanilla terrain <em>permanently</em> (terrain is not re-rolled); {@code world-nordtal: nordtal}
 * named a world that did not exist; and the plugin refused to start.
 *
 * <p>Nothing in this repository could have caught it. The two halves of the fact live in a YAML
 * file the build never read and a Java default nothing compared it against. This is the comparison.
 */
class ComposeWorldTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComposeWorldTest.class);

    /** {@code ${SMP_LEVEL_NAME:-nordtal}} - what compose falls back to when .env says nothing. */
    private static final Pattern DEFAULTED = Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$");

    @TempDir
    Path directory;

    @Test
    void composeGeneratesTheWorldTheSpecNames() throws Exception {
        final String composed = defaultOf(environmentOf("smp").get("LEVEL_NAME"), "smp.LEVEL_NAME");
        final String named = Configs.load(directory, LOGGER).get().worldNordtal();

        assertEquals(named, composed,
                "compose.yml starts the SMP on level-name '" + composed + "' while config.yml's"
                        + " world-nordtal defaults to '" + named + "'. Paper would generate one"
                        + " world, put the datapacks in it, and the plugin would look for another.");
    }

    /**
     * Nordtal is pre-generated once, to border 4000, and then frozen for the season - so the seed
     * is the one value here that cannot be corrected afterwards by editing a file.
     */
    @Test
    void theSeasonWorldsSeedIsPinned() {
        final String seed = defaultOf(environmentOf("smp").get("LEVEL_SEED"), "smp.LEVEL_SEED");
        assertTrue(seed.matches("-?\\d+"),
                "LEVEL_SEED should default to a literal seed, not to '" + seed + "'");
    }

    /** The datapacks have to land in the world Paper actually generates, not beside it. */
    @Test
    void theDatapacksGoIntoThatSameWorld() throws Exception {
        final Path entrypoint = repositoryRoot().resolve("deploy/minecraft/entrypoint.sh");
        final String script = Files.readString(entrypoint, StandardCharsets.UTF_8);

        assertTrue(script.contains("set_property \"$file\" level-name \"$LEVEL_NAME\""),
                entrypoint + " no longer writes level-name into server.properties. Without it Paper"
                        + " keeps its own default and the datapacks below go into a folder nothing"
                        + " reads.");
        assertTrue(script.contains("fetch_datapacks \"$DATA/${LEVEL_NAME}/datapacks\""),
                entrypoint + " no longer fetches the datapacks into the level-name world.");
    }

    // ---------------------------------------------------------------- reading the real file

    private static Map<String, Object> environmentOf(final String service) {
        final Path compose = repositoryRoot().resolve("compose.yml");
        try (Reader reader = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) new Yaml().load(reader);
            @SuppressWarnings("unchecked")
            final Map<String, Object> services = (Map<String, Object>) root.get("services");
            assertNotNull(services, compose + " has no services block");
            @SuppressWarnings("unchecked")
            final Map<String, Object> defined = (Map<String, Object>) services.get(service);
            assertNotNull(defined, compose + " has no service '" + service + "'");
            @SuppressWarnings("unchecked")
            final Map<String, Object> environment = (Map<String, Object>) defined.get("environment");
            assertNotNull(environment, service + " has no environment block");
            return environment;
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read " + compose, unreadable);
        }
    }

    private static String defaultOf(final Object value, final String what) {
        assertNotNull(value, "compose.yml sets no " + what);
        final Matcher matcher = DEFAULTED.matcher(String.valueOf(value));
        assertTrue(matcher.matches(),
                what + " is '" + value + "', which has no default an unfilled .env would fall back"
                        + " to. Every value here has to work without a .env entry.");
        return matcher.group(1);
    }

    /**
     * The directory holding {@code settings.gradle.kts}, not the nearest file by name.
     * <p>
     * The anchor is the rule this repository settled on 2026-09-02 after a second
     * {@code .env.example} in a module directory shadowed the real one for a walk-up by name.
     * </p>
     */
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
