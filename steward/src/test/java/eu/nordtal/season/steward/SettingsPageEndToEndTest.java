package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * smp's settings tab in Chromium, over the preview's Steward, the agent's plugin file routes and its volume.
 *
 * {@code frontend/e2e/settings-plugins.ts} drives the page; Chromium is {@code NORDTAL_CHROMIUM}.
 */
class SettingsPageEndToEndTest {

    @TempDir
    Path scratch;

    @Test
    void theTabListsTheNordtalGroupsThenExactlyThePluginFilesAndASaveWritesOnlyItsFile() throws Exception {
        final String chromium = System.getenv("NORDTAL_CHROMIUM");
        Assumptions.assumeTrue(chromium != null, "NORDTAL_CHROMIUM names no Chromium to drive the page with.");
        final Path state = scratch.resolve("state.json");
        final Path log = scratch.resolve("browser.log");
        try (StewardPreview.Running preview =
                StewardPreview.start(new StewardPreview.Options(freePort(), null, state, null))) {
            final Process browser = new ProcessBuilder(
                            property("steward.node"),
                            RepositoryRoot.resolve("steward/frontend/e2e/settings-plugins.ts")
                                    .toString(),
                            preview.base(),
                            state.toString(),
                            preview.volumes().toString(),
                            property("steward.shots"),
                            chromium)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            final boolean ended = browser.waitFor(3, TimeUnit.MINUTES);
            browser.destroyForcibly();
            final String output = Files.readString(log);
            assertTrue(ended, "the browser run did not end within 3 minutes: " + output);
            assertEquals(0, browser.exitValue(), output);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String property(final String name) {
        final @Nullable String value = System.getProperty(name);
        if (value == null) {
            throw new IllegalStateException(name + " is not set: steward's build.gradle.kts sets it for the tests");
        }
        return value;
    }
}
