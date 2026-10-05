package eu.nordtal.season.hungergames.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Checks that the world {@code compose.yml} tells Paper to generate is the world this plugin runs in.
 *
 * The plugin never creates its world and disables itself when the configured one is not loaded.
 */
class ComposeWorldTest {

    /** {@code ${HUNGER_GAMES_LEVEL_NAME:-hunger_games}}: the fallback an unfilled .env leaves. */
    private static final Pattern DEFAULTED = Pattern.compile("^\\$\\{[A-Z0-9_]+:-(.*)}$");

    @Test
    void composeGeneratesTheWorldTheSpecNames() throws Exception {
        final String composed = defaultOf(environmentOf("hunger-games").get("LEVEL_NAME"), "hunger-games.LEVEL_NAME");
        final String named = new MemorySettingStore()
                .checked(
                        "hunger-games",
                        Group.of("config", HungerGamesSpec.class).checkedBy(HungerGamesCheck::check),
                        Map.of())
                .worldName();

        assertEquals(
                named,
                composed,
                "compose.yml starts the event server on level-name '" + composed + "' while"
                        + " config.yml's world-name defaults to '" + named + "'. The plugin does not"
                        + " load a world of its own - it disables itself when that one is missing.");
    }

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
        assertTrue(
                matcher.matches(),
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
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
