package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A value steward-agent's container was made with never hides a later edit of the env file from compose. */
class EnvFileOutranksInheritedEnvironmentTest {

    @TempDir
    Path directory;

    @Test
    void aNameTheEnvFileSetsIsLeftToTheFile() throws IOException {
        final Compose compose = compose("COMPOSE_PROFILES=db,mc\nexport TZ=UTC\n# NORDTAL_IMAGES=commented\n");

        final Map<String, String> environment = compose.commandEnvironment(
                Map.of("COMPOSE_PROFILES", "db,bot,mc,steward", "TZ", "Europe/Berlin", "PATH", "/bin"));

        assertFalse(environment.containsKey("COMPOSE_PROFILES"), environment.toString());
        assertFalse(environment.containsKey("TZ"), environment.toString());
        assertEquals("/bin", environment.get("PATH"));
    }

    @Test
    void aNameTheEnvFileDoesNotSetIsInherited() throws IOException {
        final Compose compose = compose("COMPOSE_PROFILES=db,mc\n# NORDTAL_IMAGES=commented\n");

        final Map<String, String> environment = compose.commandEnvironment(Map.of("NORDTAL_IMAGES", "ghcr.io/nordtal"));

        assertEquals("ghcr.io/nordtal", environment.get("NORDTAL_IMAGES"));
    }

    @Test
    void theReleaseACommandIsForOutranksEverything() throws IOException {
        final Compose compose = compose("NORDTAL_RELEASE=0.1.0\n").atRelease("9.9.9");

        final Map<String, String> environment = compose.commandEnvironment(Map.of("NORDTAL_RELEASE", "0.2.0"));

        assertEquals("9.9.9", environment.get("NORDTAL_RELEASE"));
    }

    private Compose compose(final String envFile) throws IOException {
        final Path file = directory.resolve("season-2.env");
        Files.writeString(file, envFile, StandardCharsets.UTF_8);
        return new Compose(Path.of("/app/compose.yml"), file, Path.of("/app"), "nordtal-s2");
    }
}
