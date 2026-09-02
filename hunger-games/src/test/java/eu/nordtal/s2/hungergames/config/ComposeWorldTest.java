package eu.nordtal.s2.hungergames.config;

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
 * That the world {@code compose.yml} tells Paper to generate is the world this plugin runs in.
 *
 * <h2>Why this file exists</h2>
 * This module does <b>not</b> create its world - the event map is hand-built and shipped as a
 * folder, and the plugin disables itself when the world is not loaded. Until 2026-09-02 the
 * {@code hunger-games} service had no {@code LEVEL_NAME} at all, so Paper generated {@code world}
 * while {@code config.yml}'s {@code world-name} said {@code hunger_games} - and
 * {@code deploy/README.md} told the operator to {@code docker cp} the map into {@code /data/world/},
 * which produces a world under the one name the plugin will not look for. Following the documented
 * runbook to the letter left this module permanently disabled, and nothing here could see it: the
 * two halves of the fact were a YAML file the build never read and a Java default nothing compared
 * it against.
 */
class ComposeWorldTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComposeWorldTest.class);

    /** {@code ${HUNGER_GAMES_LEVEL_NAME:-hunger_games}} - the fallback an unfilled .env leaves. */
    private static final Pattern DEFAULTED = Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$");

    @TempDir
    Path directory;

    @Test
    void composeGeneratesTheWorldTheSpecNames() throws Exception {
        final String composed =
                defaultOf(environmentOf("hunger-games").get("LEVEL_NAME"), "hunger-games.LEVEL_NAME");
        final String named = Configs.load(directory, LOGGER).get().worldName();

        assertEquals(named, composed,
                "compose.yml starts the event server on level-name '" + composed + "' while"
                        + " config.yml's world-name defaults to '" + named + "'. The plugin does not"
                        + " load a world of its own - it disables itself when that one is missing.");
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
