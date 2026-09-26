package eu.nordtal.jcore.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.internal.EnvOverlay;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Point 4 of the change: one mechanism for overriding any value from the environment. */
class EnvOverlayTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("payments.yml");
    }

    @Test
    void environmentWinsOverFile() throws Exception {
        Files.writeString(file(), "check-interval-seconds: 5\n");

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .environment(Map.of("NORDTAL_CHECK_INTERVAL_SECONDS", "60")::get)
                .load();

        assertEquals(60L, handle.get().checkIntervalSeconds());
    }

    @Test
    void overrideIsNotPersisted() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .environment(Map.of("NORDTAL_BALANCE_CHANNEL_ID", "s3cret-channel")::get)
                .load();

        assertEquals("s3cret-channel", handle.get().balance().channelId(), "the override applies");

        final String content = Files.readString(file());
        assertAll(
                () -> assertFalse(
                        content.contains("s3cret-channel"),
                        "an environment value could be a secret and must never reach the file"),
                () -> assertTrue(content.contains("1417574134958788720"), "the file keeps its own value"));
    }

    @Test
    void saveDoesNotPersistOverride() throws Exception {
        Files.writeString(file(), """
                check-interval-seconds: 5
                confirmation-channel-id: '111'
                balance:
                  channel-id: '222'
                  format: '%s EUR'
                """);

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .environment(Map.of("NORDTAL_BALANCE_CHANNEL_ID", "from-env")::get)
                .load();

        handle.save();

        final String content = Files.readString(file());
        assertAll(
                () -> assertFalse(content.contains("from-env"), "save() must not leak the override"),
                () -> assertTrue(content.contains("'222'"), "the file's own value is written back"),
                () -> assertEquals(
                        "from-env",
                        handle.get().balance().channelId(),
                        "the override is still in effect in memory after the save"));
    }

    @Test
    void reportsOverriddenPaths() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .environment(Map.of(
                        "NORDTAL_CHECK_INTERVAL_SECONDS", "30",
                        "NORDTAL_BALANCE_FORMAT", "%s eur")::get)
                .load();

        assertEquals(java.util.List.of("check-interval-seconds", "balance.format"), handle.environmentOverrides());
    }

    @Test
    void blankVariableIsUnset() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .environment(Map.of("NORDTAL_CHECK_INTERVAL_SECONDS", "  ")::get)
                .load();

        assertAll(
                () -> assertEquals(10L, handle.get().checkIntervalSeconds()),
                () -> assertTrue(handle.environmentOverrides().isEmpty()));
    }

    @Test
    void unparseableValueIsRefused() {
        final ConfigValidationException error = assertThrows(
                ConfigValidationException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .environment(Map.of("NORDTAL_CHECK_INTERVAL_SECONDS", "hunter2")::get)
                        .load());

        assertAll(
                () -> assertTrue(error.getMessage().contains("NORDTAL_CHECK_INTERVAL_SECONDS")),
                () -> assertFalse(
                        error.getMessage().contains("hunter2"), "the value could be a secret and must not be logged"));
    }

    @Test
    void variableNaming() {
        assertAll(
                () -> assertEquals(
                        "NORDTAL_BALANCE_CHANNEL_ID", EnvOverlay.variableName("NORDTAL", "balance.channel-id")),
                () -> assertEquals(
                        "NORDTAL_CHECK_INTERVAL_SECONDS",
                        EnvOverlay.variableName("NORDTAL", "check-interval-seconds")));
    }

    @Test
    void collidingVariableNamesAreRejected() {
        // 'a-b' and 'a.b' both become NORDTAL_A_B; that has to fail at the first load, not silently.
        final IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> ConfigLoader.builder(directory.resolve("colliding.yml"), TestSpecs.Colliding.class)
                        .withoutEnvironmentOverlay()
                        .load());

        assertAll(
                () -> assertTrue(error.getMessage().contains("NORDTAL_A_B"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("a-b"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("a.b"), error.getMessage()));
    }
}
