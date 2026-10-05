package eu.nordtal.season.proxy.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The one value in {@code compose.yml} that tells the two proxies apart.
 *
 * Nothing compiles that file, and a missing or misplaced value fails silently in both directions.
 */
class ComposeTellsTheStandbyApartTest {

    private static final String KEY = "NORDTAL_PROXY_NETWORK_STANDBY";

    @Test
    void composeNamesTheStandby() throws IOException {
        final String compose = Files.readString(repositoryRoot().resolve("compose.yml"), StandardCharsets.UTF_8);

        assertEquals(1, occurrences(compose, "  proxy:"), "there should be exactly one `proxy` service in compose.yml");
        assertEquals(
                1,
                occurrences(compose, "  proxy-standby:"),
                "there should be exactly one `proxy-standby` service in compose.yml");

        assertEquals(
                "\"false\"",
                valueOf(block(compose, "  proxy:"), KEY),
                KEY + " under `proxy` has to be false: that process is the one players connect to,"
                        + " and a live proxy that thinks it is the standby transfers everybody to"
                        + " the address they are already on");
        assertEquals(
                "\"true\"",
                valueOf(block(compose, "  proxy-standby:"), KEY),
                KEY + " under `proxy-standby` has to be true, and it is the ONLY thing that makes"
                        + " that container a standby: without it the container starts, looks"
                        + " healthy, releases parked players onto the backends and never sends"
                        + " anybody home");

        assertTrue(
                block(compose, "  proxy-standby:").contains("<<: *proxy-env"),
                "the standby has to MERGE the shared environment rather than replace it, or every"
                        + " setting the live proxy gains from now on reaches only one of them");
    }

    /** The lines of one compose service, from its key to the next key at the same indent. */
    private static String block(final String compose, final String service) {
        final int start = compose.indexOf(System.lineSeparator() + service);
        assertTrue(start >= 0, "no `" + service.trim() + "` in compose.yml");
        int end = start + System.lineSeparator().length() + service.length();
        for (final String line : compose.substring(end).lines().toList()) {
            if (line.startsWith("  ") && !line.startsWith("   ") && line.trim().endsWith(":")) {
                break;
            }
            end += line.length() + System.lineSeparator().length();
        }
        return compose.substring(start, Math.min(end, compose.length()));
    }

    private static String valueOf(final String block, final String key) {
        for (final String line : block.lines().toList()) {
            final String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                return trimmed.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    private static int occurrences(final String text, final String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
