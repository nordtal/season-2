package eu.nordtal.jcore.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigReadException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for {@code JsonConfigLoader}'s defects; each test names the finding it guards.
 */
class ConfigLoaderTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("payments.yml");
    }

    // finding 1

    @Test
    void loadHookRunsOnUnchangedFile() throws Exception {
        final AtomicInteger calls = new AtomicInteger();

        // First load creates the file.
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .onLoad(config -> calls.incrementAndGet())
                .load();
        assertEquals(1, calls.get(), "the hook must run on the very first load");

        final String afterFirst = Files.readString(file());

        // The file is unchanged, so the hook must still run - an unchanged file is the common case.
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .onLoad(config -> calls.incrementAndGet())
                .load();

        assertAll(
                () -> assertEquals(2, calls.get(), "the hook must run again on an unchanged file"),
                () -> assertEquals(afterFirst, Files.readString(file()), "an unchanged file must not be rewritten"));
    }

    @Test
    void loadHookRunsOnReload() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .onLoad(config -> calls.incrementAndGet())
                .load();

        handle.reload();
        handle.reload();

        assertEquals(3, calls.get(), "one initial load plus two reloads");
    }

    // finding 2

    @Test
    void unknownKeyAborts() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        // A plausible typo: one character wrong.
        final String broken = Files.readString(file()).replace("check-interval-seconds:", "check-intervall-seconds:");
        Files.writeString(file(), broken);

        final UnknownConfigKeyException error = assertThrows(
                UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());

        assertAll(
                () -> assertEquals(1, error.unknownKeys().size()),
                () -> assertEquals(
                        "check-intervall-seconds", error.unknownKeys().get(0).path()),
                () -> assertEquals(
                        "check-interval-seconds",
                        error.unknownKeys().get(0).suggestion(),
                        "the message must name the key that was probably meant"),
                () -> assertTrue(error.getMessage().contains("check-interval-seconds")));
    }

    @Test
    void unknownKeyLeavesFileUntouched() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String broken = Files.readString(file()).replace("check-interval-seconds:", "check-intervall-seconds:");
        Files.writeString(file(), broken);

        assertThrows(
                UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());

        // A failed load must not touch the file at all.
        assertEquals(broken, Files.readString(file()), "a failed load must not modify the file in any way");
        assertFalse(
                Files.exists(directory.resolve("payments.yml.bak")),
                "nothing was written, so nothing should have been backed up");
    }

    @Test
    void unknownKeyInNestedSection() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String broken = Files.readString(file()).replace("  format:", "  formatt:");
        Files.writeString(file(), broken);

        final UnknownConfigKeyException error = assertThrows(
                UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());

        assertAll(
                () -> assertEquals("balance.formatt", error.unknownKeys().get(0).path()),
                () -> assertEquals("format", error.unknownKeys().get(0).suggestion()));
    }

    // finding 2b

    @Test
    void unknownKeyInsideListElement() throws Exception {
        final Path worlds = directory.resolve("worlds.yml");
        Files.writeString(worlds, """
                worlds:
                - name: farm
                  display-colour: '#00ff00'
                  preserved: false
                - name: spawn
                  display-color: '#ff0000'
                  preserved: true
                reset-day: monday
                """);

        final UnknownConfigKeyException error = assertThrows(
                UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(worlds, TestSpecs.Worlds.class)
                        .withoutEnvironmentOverlay()
                        .load());

        // An unknown key inside a list element must be found too, with its index.
        assertAll(
                () -> assertEquals(1, error.unknownKeys().size()),
                () -> assertEquals(
                        "worlds[1].display-color",
                        error.unknownKeys().get(0).path(),
                        "the index is what makes this actionable in a long list"),
                () -> assertEquals("display-colour", error.unknownKeys().get(0).suggestion()));
    }

    @Test
    void listOfNestedObjectsRoundTrips() throws Exception {
        final Path worlds = directory.resolve("worlds.yml");
        Files.writeString(worlds, """
                worlds:
                - name: farm
                  display-colour: '#00ff00'
                  preserved: false
                - name: spawn
                  display-colour: '#ff0000'
                  preserved: true
                reset-day: sunday
                """);

        final TestSpecs.Worlds config = ConfigLoader.builder(worlds, TestSpecs.Worlds.class)
                .withoutEnvironmentOverlay()
                .load()
                .get();

        assertAll(
                () -> assertEquals(2, config.worlds().size()),
                () -> assertEquals("farm", config.worlds().get(0).name()),
                () -> assertEquals("#ff0000", config.worlds().get(1).displayColour()),
                () -> assertTrue(config.worlds().get(1).preserved()),
                () -> assertEquals("sunday", config.resetDay()));
    }

    @Test
    void listOfNestedObjectsSurvivesSaveAndReload() throws Exception {
        final Path worlds = directory.resolve("worlds.yml");
        Files.writeString(worlds, """
                worlds:
                - name: farm
                  display-colour: '#00ff00'
                  preserved: false
                - name: spawn
                  display-colour: '#ff0000'
                  preserved: true
                reset-day: sunday
                """);

        final ConfigHandle<TestSpecs.Worlds> handle = ConfigLoader.builder(worlds, TestSpecs.Worlds.class)
                .withoutEnvironmentOverlay()
                .load();

        // Gson serializes a list element by its runtime type - the generated Proxy class, not the interface.
        handle.save();
        handle.reload();

        final String content = Files.readString(worlds);
        assertAll(
                () -> assertEquals(2, handle.get().worlds().size()),
                () -> assertEquals("spawn", handle.get().worlds().get(1).name()),
                () -> assertTrue(handle.get().worlds().get(1).preserved()),
                () -> assertTrue(content.contains("name: farm"), content),
                () -> assertTrue(content.contains("display-colour: '#ff0000'"), content),
                () -> assertEquals("sunday", handle.get().resetDay()));
    }

    // finding 4

    @Test
    void bareRelativeFileName() {
        // new File("config.yml").getParentFile() is null, and the old loader called mkdirs() on it unguarded.
        final Path bare = Path.of("jcore-config-test-" + System.nanoTime() + ".yml");
        try {
            assertEquals(
                    10L,
                    ConfigLoader.builder(bare, TestSpecs.Payments.class)
                            .withoutEnvironmentOverlay()
                            .load()
                            .get()
                            .checkIntervalSeconds());
            assertTrue(Files.isRegularFile(bare));
        } catch (ConfigException e) {
            throw new AssertionError("loading a bare relative filename must work", e);
        } finally {
            try {
                Files.deleteIfExists(bare);
                Files.deleteIfExists(Path.of(bare + ".bak"));
                Files.deleteIfExists(eu.nordtal.jcore.config.schema.SchemaWriter.schemaFileFor(bare));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // finding 5

    @Test
    void createsMissingParentDirectories() throws Exception {
        final Path nested = directory.resolve("a/b/c/payments.yml");

        final TestSpecs.Payments config = ConfigLoader.builder(nested, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load()
                .get();

        // mkdirs() succeeding must not skip the file creation that follows it.
        assertAll(
                () -> assertTrue(Files.isRegularFile(nested), "the file itself must exist"),
                () -> assertEquals(10L, config.checkIntervalSeconds()));
    }

    // finding 9

    @Test
    void missingSettingIsAddedAndBackedUp() throws Exception {
        Files.writeString(file(), """
                check-interval-seconds: 42
                """);

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String written = Files.readString(file());
        assertAll(
                () -> assertEquals(42L, handle.get().checkIntervalSeconds(), "the operator's value survives"),
                () -> assertTrue(written.contains("confirmation-channel-id:"), "the missing setting is added"),
                () -> assertTrue(written.contains("balance:"), "the missing section is added"),
                () -> assertFalse(
                        written.contains("#"),
                        "the YAML carries no comments at all - see"
                                + " eu.nordtal.jcore.config.schema.SchemaWriter for where the explanations went"),
                () -> assertTrue(
                        Files.isRegularFile(directory.resolve("payments.yml.bak")),
                        "the previous content is preserved before any rewrite"),
                () -> assertEquals(
                        "check-interval-seconds: 42\n", Files.readString(directory.resolve("payments.yml.bak"))));
    }

    @Test
    void wholeNumbersStayWhole() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        // Gson's default number policy reads 10 back as a Double; ConfigLoader sets LONG_OR_DOUBLE to avoid that.
        final String content = Files.readString(file());
        assertAll(
                () -> assertTrue(content.contains("check-interval-seconds: 10"), content),
                () -> assertFalse(content.contains("10.0"), content));
    }

    // : comment-free YAML

    @Test
    void commentsAndNewSettingsReachAnExistingFile() throws Exception {
        // Fixture: correct keys, an operator's own comment, one section missing entirely.
        Files.writeString(file(), """
                # An outdated comment nobody rewrote
                check-interval-seconds: 99
                confirmation-channel-id: '555'
                """);

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String content = Files.readString(file());
        assertAll(
                () -> assertEquals(99L, handle.get().checkIntervalSeconds(), "operator value kept"),
                () -> assertEquals("555", handle.get().confirmationChannelId(), "operator value kept"),
                () -> assertFalse(
                        content.contains("An outdated comment nobody rewrote"),
                        "an operator's own comment does not survive a rewrite"),
                () -> assertFalse(
                        content.lines().anyMatch(line -> line.strip().startsWith("#")),
                        "the file carries no comments at all - not the header, not @Comment's text"),
                () -> assertTrue(
                        content.contains("channel-id: '1417574134958788720'"),
                        "a section added to the spec since reaches the file with its default"));
    }

    @Test
    void noHeaderOrCommentAppearsAcrossRepeatedLoads() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final long commentLines = Files.readAllLines(file()).stream()
                .filter(line -> line.strip().startsWith("#"))
                .count();
        assertEquals(0, commentLines, "@ConfigSpec's header text goes into the schema, never into the YAML");
    }

    // validation

    @Test
    void validationRejectsBadValues() throws Exception {
        Files.writeString(file(), """
                check-interval-seconds: -5
                """);

        final ConfigValidationException error = assertThrows(
                ConfigValidationException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .validator(config -> {
                            if (config.checkIntervalSeconds() <= 0) {
                                throw new IllegalArgumentException("check-interval-seconds must be positive, was "
                                        + config.checkIntervalSeconds());
                            }
                        })
                        .load());

        assertAll(
                () -> assertTrue(error.getMessage().contains("must be positive")),
                () -> assertTrue(error.getMessage().contains("payments.yml"), "the message names the file"));
    }

    @Test
    void validationCoversEnvironmentValues() throws Exception {
        final ConfigValidationException error = assertThrows(
                ConfigValidationException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .environment(name -> "NORDTAL_CHECK_INTERVAL_SECONDS".equals(name) ? "-1" : null)
                        .validator(config -> {
                            if (config.checkIntervalSeconds() <= 0) {
                                throw new IllegalArgumentException("check-interval-seconds must be positive");
                            }
                        })
                        .load());

        assertTrue(error.getMessage().contains("must be positive"));
    }

    // broken file

    @Test
    void brokenFileIsReported() throws Exception {
        Files.writeString(file(), "check-interval-seconds: 10\n  : : oops\n\tbad tab");

        assertThrows(
                ConfigReadException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());
    }

    @Test
    void nonMappingRootIsReported() throws Exception {
        Files.writeString(file(), "- just\n- a\n- list\n");

        final ConfigReadException error = assertThrows(
                ConfigReadException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());

        assertTrue(error.getMessage().contains("payments.yml"));
    }

    @Test
    void yamlLoaderIsSafe() throws Exception {
        // A bare `new Yaml()` honours explicit tags and would try to construct this type.
        Files.writeString(file(), """
                check-interval-seconds: !!java.net.URLClassLoader [[]]
                """);

        assertThrows(
                ConfigReadException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());
    }

    // reload

    @Test
    void reloadPicksUpChanges() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        final TestSpecs.Payments config = handle.get();

        assertEquals(10L, config.checkIntervalSeconds());

        Files.writeString(
                file(), Files.readString(file()).replace("check-interval-seconds: 10", "check-interval-seconds: 77"));
        handle.reload();

        assertEquals(77L, config.checkIntervalSeconds(), "the instance handed out earlier must see the new value");
    }

    @Test
    void specReloadAnnotationWorks() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        final TestSpecs.Payments config = handle.get();

        Files.writeString(
                file(), Files.readString(file()).replace("check-interval-seconds: 10", "check-interval-seconds: 33"));
        config.reload();

        assertEquals(33L, config.checkIntervalSeconds());
    }

    @Test
    void failedValidationOnReloadKeepsOldValues() throws Exception {
        final ConfigValidator<TestSpecs.Payments> validator = config -> {
            if (config.checkIntervalSeconds() <= 0) {
                throw new IllegalArgumentException("check-interval-seconds must be positive");
            }
        };
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .validator(validator)
                .load();
        final TestSpecs.Payments config = handle.get();

        Files.writeString(
                file(), Files.readString(file()).replace("check-interval-seconds: 10", "check-interval-seconds: -1"));

        assertThrows(ConfigValidationException.class, handle::reload);
        assertEquals(10L, config.checkIntervalSeconds(), "values the application rejected must never become visible");
    }

    @Test
    void failedReloadKeepsOldValues() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        final TestSpecs.Payments config = handle.get();

        Files.writeString(
                file(), Files.readString(file()).replace("check-interval-seconds:", "check-interval-secondz:"));

        assertThrows(UnknownConfigKeyException.class, handle::reload);
        assertEquals(10L, config.checkIntervalSeconds(), "a rejected reload must not leave the config half-applied");
    }

    // finding 2c

    /*
     * The other half of finding 2. A key the spec does not declare is either
     * a slip of the keyboard or a setting the software has since removed, and until now both
     * stopped the process. The second one has nothing an operator can fix: the line is dead, the
     * only possible edit is to delete it, and refusing to start until they do costs a whole
     * network for a key that already means nothing. What tells the two apart is whether a
     * declared key is close enough to name.
     */

    @Test
    void retiredKeyIsDropped() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        // No declared key is close enough - this is what a retired setting looks like to the loader.
        Files.writeString(file(), Files.readString(file()) + "legacy-contribution-tiers: 3" + System.lineSeparator());

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        assertAll(
                () -> assertFalse(
                        Files.readString(file()).contains("legacy-contribution-tiers"),
                        "the retired key must be gone from the file"),
                () -> assertEquals(
                        10L, handle.get().checkIntervalSeconds(), "every setting that still exists keeps its value"),
                () -> assertTrue(
                        Files.readString(directory.resolve("payments.yml.bak")).contains("legacy-contribution-tiers"),
                        "what was deleted has to be recoverable"));
    }

    @Test
    void retiredKeyInNestedSectionIsDropped() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        Files.writeString(
                file(),
                Files.readString(file())
                        .replace(
                                "  format:",
                                "  legacy-voice-announcement: true" + System.lineSeparator() + "  format:"));

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        assertAll(
                () -> assertFalse(Files.readString(file()).contains("legacy-voice-announcement")),
                () -> assertEquals("%s EUR", handle.get().balance().format()));
    }

    @Test
    void retiredKeyInsideListElementIsDropped() throws Exception {
        final Path worlds = directory.resolve("worlds.yml");
        Files.writeString(worlds, """
                worlds:
                - name: farm
                  display-colour: '#00ff00'
                  preserved: false
                - name: spawn
                  display-colour: '#ff0000'
                  legacy-bossbar-title: 'Spawn'
                  preserved: true
                reset-day: monday
                """);

        final ConfigHandle<TestSpecs.Worlds> handle = ConfigLoader.builder(worlds, TestSpecs.Worlds.class)
                .withoutEnvironmentOverlay()
                .load();

        assertAll(
                () -> assertFalse(Files.readString(worlds).contains("legacy-bossbar-title")),
                () -> assertEquals(2, handle.get().worlds().size()),
                () -> assertEquals("spawn", handle.get().worlds().get(1).name()),
                () -> assertTrue(
                        handle.get().worlds().get(1).preserved(),
                        "the sibling settings of a deleted key are not collateral"));
    }

    @Test
    void misspellingWinsOverARetiredKey() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String broken = Files.readString(file()).replace("check-interval-seconds:", "check-intervall-seconds:")
                + "legacy-contribution-tiers: 3" + System.lineSeparator();
        Files.writeString(file(), broken);

        final UnknownConfigKeyException error = assertThrows(
                UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .load());

        assertAll(
                () -> assertEquals(1, error.unknownKeys().size(), "only the key the operator can act on is named"),
                () -> assertEquals(
                        "check-intervall-seconds", error.unknownKeys().get(0).path()),
                // A refused load performs no write, so the retired key survives until the typo is fixed.
                () -> assertEquals(
                        broken,
                        Files.readString(file()),
                        "a refused load writes nothing at all, so the retired line is still there"));
    }

    @Test
    void retiredKeyIsDroppedOnReload() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        Files.writeString(
                file(),
                Files.readString(file()).replace("check-interval-seconds: 10", "check-interval-seconds: 25")
                        + "legacy-contribution-tiers: 3" + System.lineSeparator());

        handle.reload();

        assertAll(
                () -> assertEquals(
                        25L,
                        handle.get().checkIntervalSeconds(),
                        "the reload has to have gone through, not been refused"),
                () -> assertFalse(Files.readString(file()).contains("legacy-contribution-tiers")));
    }
}
