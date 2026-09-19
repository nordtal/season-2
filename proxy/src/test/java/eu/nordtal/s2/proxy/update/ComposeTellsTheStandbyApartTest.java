package eu.nordtal.s2.proxy.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one value in {@code compose.yml} that tells the two proxies apart (season-2-ops/121).
 *
 * <h2>Why a test reads a YAML file</h2>
 * Everything else about this feature is Java and is held by the compiler. This is not: the two
 * proxies are one image and one environment block, and the entire difference between "the proxy
 * players connect to" and "the proxy that holds them while the first one restarts" is one string in
 * a file nothing compiles.
 *
 * <p><b>Both directions are failures and neither says anything.</b> A standby that was not told it
 * is one behaves exactly like the live proxy: it releases parked players onto the backends and
 * never sends anybody home, and the log line it prints is the ordinary one. A live proxy that was
 * told it <em>is</em> the standby watches the public address, finds itself answering, and
 * transfers every player to the address they are already on.</p>
 *
 * <p>This does not prove the deployment works - see {@code fits-on-a-phone.test.ts} in the frontend
 * for the same caveat, written for the same reason. It proves that the line was not deleted or
 * copied to the wrong service, which is the way it will actually go wrong.</p>
 */
class ComposeTellsTheStandbyApartTest {

    private static final String KEY = "NORDTAL_PROXY_NETWORK_STANDBY";

    @Test
    @DisplayName("compose.yml says false for the live proxy and true for the standby, once each")
    void composeNamesTheStandby() throws IOException {
        final String compose = Files.readString(repositoryRoot().resolve("compose.yml"),
                StandardCharsets.UTF_8);

        assertEquals(1, occurrences(compose, "  proxy:"),
                "there should be exactly one `proxy` service in compose.yml");
        assertEquals(1, occurrences(compose, "  proxy-standby:"),
                "there should be exactly one `proxy-standby` service in compose.yml");

        assertEquals("\"false\"", valueOf(block(compose, "  proxy:"), KEY),
                KEY + " under `proxy` has to be false: that process is the one players connect to,"
                        + " and a live proxy that thinks it is the standby transfers everybody to"
                        + " the address they are already on");
        assertEquals("\"true\"", valueOf(block(compose, "  proxy-standby:"), KEY),
                KEY + " under `proxy-standby` has to be true, and it is the ONLY thing that makes"
                        + " that container a standby: without it the container starts, looks"
                        + " healthy, releases parked players onto the backends and never sends"
                        + " anybody home");

        assertTrue(block(compose, "  proxy-standby:").contains("<<: *proxy-env"),
                "the standby has to MERGE the shared environment rather than replace it, or every"
                        + " setting the live proxy gains from now on reaches only one of them");
    }

    /**
     * The lines of one compose service: from its key to the next key at the same indent.
     *
     * <p>Two spaces and a colon, and the third character not a space - which is exactly how a
     * compose service is written and nothing else in the file is.</p>
     */
    private static String block(final String compose, final String service) {
        final int start = compose.indexOf(System.lineSeparator() + service);
        assertTrue(start >= 0, "no `" + service.trim() + "` in compose.yml");
        int end = start + System.lineSeparator().length() + service.length();
        for (final String line : compose.substring(end).split("\\R")) {
            if (line.startsWith("  ") && !line.startsWith("   ") && line.trim().endsWith(":")) {
                break;
            }
            end += line.length() + System.lineSeparator().length();
        }
        return compose.substring(start, Math.min(end, compose.length()));
    }

    private static String valueOf(final String block, final String key) {
        for (final String line : block.split("\\R")) {
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
