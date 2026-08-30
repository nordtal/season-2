package eu.nordtal.jcore.config;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigReadException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the defects found in the old {@code JsonConfigLoader}. Each test names the finding it
 * belongs to and what the old loader did instead.
 */
class ConfigLoaderTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("payments.yml");
    }

    // ---------------------------------------------------------------- finding 1

    @Test
    @DisplayName("finding 1: the load hook runs even when the file already matches the spec")
    void loadHookRunsOnUnchangedFile() throws Exception {
        final AtomicInteger calls = new AtomicInteger();

        // First load creates the file.
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .onLoad(config -> calls.incrementAndGet())
                .load();
        assertEquals(1, calls.get(), "the hook must run on the very first load");

        final String afterFirst = Files.readString(file());

        // Second load: the file is byte-identical to what the spec would write, so the old
        // loader's `differences.isEmpty()` short-circuit returned before ever calling
        // postLoad(). This is the normal case, which is what made it so damaging.
        ConfigLoader.builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .onLoad(config -> calls.incrementAndGet())
                .load();

        assertAll(
                () -> assertEquals(2, calls.get(), "the hook must run again on an unchanged file"),
                () -> assertEquals(afterFirst, Files.readString(file()),
                        "an unchanged file must not be rewritten")
        );
    }

    @Test
    @DisplayName("finding 1: the load hook also runs on every reload")
    void loadHookRunsOnReload() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .onLoad(config -> calls.incrementAndGet())
                .load();

        handle.reload();
        handle.reload();

        assertEquals(3, calls.get(), "one initial load plus two reloads");
    }

    // ---------------------------------------------------------------- finding 2

    @Test
    @DisplayName("finding 2: an unknown top-level key aborts and names the key that was meant")
    void unknownKeyAborts() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        // A plausible typo: one character wrong.
        final String broken = Files.readString(file())
                .replace("check-interval-seconds:", "check-intervall-seconds:");
        Files.writeString(file(), broken);

        final UnknownConfigKeyException error = assertThrows(UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay().load());

        assertAll(
                () -> assertEquals(1, error.unknownKeys().size()),
                () -> assertEquals("check-intervall-seconds", error.unknownKeys().get(0).path()),
                () -> assertEquals("check-interval-seconds", error.unknownKeys().get(0).suggestion(),
                        "the message must name the key that was probably meant"),
                () -> assertTrue(error.getMessage().contains("check-interval-seconds"))
        );
    }

    @Test
    @DisplayName("finding 2: the file is never trimmed - the mistyped line survives verbatim")
    void unknownKeyLeavesFileUntouched() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final String broken = Files.readString(file())
                .replace("check-interval-seconds:", "check-intervall-seconds:");
        Files.writeString(file(), broken);

        assertThrows(UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay().load());

        // The old loader wrote the reconciled instance back, which deleted the operator's line
        // with no warning and no backup.
        assertEquals(broken, Files.readString(file()),
                "a failed load must not modify the file in any way");
        assertFalse(Files.exists(directory.resolve("payments.yml.bak")),
                "nothing was written, so nothing should have been backed up");
    }

    @Test
    @DisplayName("finding 2: an unknown key inside a nested section is found with its full path")
    void unknownKeyInNestedSection() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final String broken = Files.readString(file()).replace("  format:", "  formatt:");
        Files.writeString(file(), broken);

        final UnknownConfigKeyException error = assertThrows(UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay().load());

        assertAll(
                () -> assertEquals("balance.formatt", error.unknownKeys().get(0).path()),
                () -> assertEquals("format", error.unknownKeys().get(0).suggestion())
        );
    }

    // ---------------------------------------------------------------- finding 2b

    @Test
    @DisplayName("finding 2b: an unknown key inside a list element is found, with its index")
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

        final UnknownConfigKeyException error = assertThrows(UnknownConfigKeyException.class,
                () -> ConfigLoader.builder(worlds, TestSpecs.Worlds.class)
                        .withoutEnvironmentOverlay().load());

        // The old diff never descended into arrays at all. The value silently fell back to the
        // default while the mistyped line stayed visible in the file, so the operator kept
        // reading a setting that did nothing.
        assertAll(
                () -> assertEquals(1, error.unknownKeys().size()),
                () -> assertEquals("worlds[1].display-color", error.unknownKeys().get(0).path(),
                        "the index is what makes this actionable in a long list"),
                () -> assertEquals("display-colour", error.unknownKeys().get(0).suggestion())
        );
    }

    @Test
    @DisplayName("a correct list of nested objects round-trips with its values intact")
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

        final TestSpecs.Worlds config = ConfigLoader
                .builder(worlds, TestSpecs.Worlds.class).withoutEnvironmentOverlay().load().get();

        assertAll(
                () -> assertEquals(2, config.worlds().size()),
                () -> assertEquals("farm", config.worlds().get(0).name()),
                () -> assertEquals("#ff0000", config.worlds().get(1).displayColour()),
                () -> assertTrue(config.worlds().get(1).preserved()),
                () -> assertEquals("sunday", config.resetDay())
        );
    }

    @Test
    @DisplayName("a list of nested objects survives save() and a reload")
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

        final ConfigHandle<TestSpecs.Worlds> handle = ConfigLoader
                .builder(worlds, TestSpecs.Worlds.class).withoutEnvironmentOverlay().load();

        // Upstream Spec could not write this at all: Gson serializes a list element by its
        // runtime type, which is a Proxy class rather than the spec interface, and fell through
        // to reflective serialization of java.lang.reflect.Proxy#h.
        handle.save();
        handle.reload();

        final String content = Files.readString(worlds);
        assertAll(
                () -> assertEquals(2, handle.get().worlds().size()),
                () -> assertEquals("spawn", handle.get().worlds().get(1).name()),
                () -> assertTrue(handle.get().worlds().get(1).preserved()),
                () -> assertTrue(content.contains("name: farm"), content),
                () -> assertTrue(content.contains("display-colour: '#ff0000'"), content),
                () -> assertEquals("sunday", handle.get().resetDay())
        );
    }

    // ---------------------------------------------------------------- finding 4

    @Test
    @DisplayName("finding 4: a bare relative filename with no parent directory does not throw")
    void bareRelativeFileName() {
        // new File("config.yml").getParentFile() is null, and the old loader called mkdirs() on
        // it unguarded.
        final Path bare = Path.of("jcore-config-test-" + System.nanoTime() + ".yml");
        try {
            assertEquals(10L, ConfigLoader.builder(bare, TestSpecs.Payments.class)
                    .withoutEnvironmentOverlay().load().get().checkIntervalSeconds());
            assertTrue(Files.isRegularFile(bare));
        } catch (ConfigException e) {
            throw new AssertionError("loading a bare relative filename must work", e);
        } finally {
            try {
                Files.deleteIfExists(bare);
                Files.deleteIfExists(Path.of(bare + ".bak"));
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    // ---------------------------------------------------------------- finding 5

    @Test
    @DisplayName("finding 5: a missing parent directory is created and the file is written")
    void createsMissingParentDirectories() throws Exception {
        final Path nested = directory.resolve("a/b/c/payments.yml");

        final TestSpecs.Payments config = ConfigLoader.builder(nested, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay().load().get();

        // The old code's `mkdirs() || createNewFile()` short-circuit meant createNewFile() never
        // ran when mkdirs() succeeded; the construct only decided whether a log line appeared.
        assertAll(
                () -> assertTrue(Files.isRegularFile(nested), "the file itself must exist"),
                () -> assertEquals(10L, config.checkIntervalSeconds())
        );
    }

    // ---------------------------------------------------------------- finding 9

    @Test
    @DisplayName("finding 9: a file missing a setting is normalised, and a backup is kept")
    void missingSettingIsAddedAndBackedUp() throws Exception {
        Files.writeString(file(), """
                check-interval-seconds: 42
                """);

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final String written = Files.readString(file());
        assertAll(
                () -> assertEquals(42L, handle.get().checkIntervalSeconds(), "the operator's value survives"),
                () -> assertTrue(written.contains("confirmation-channel-id:"), "the missing setting is added"),
                () -> assertTrue(written.contains("balance:"), "the missing section is added"),
                () -> assertTrue(written.contains("# How often the account is polled, in seconds."),
                        "comments are written"),
                () -> assertTrue(Files.isRegularFile(directory.resolve("payments.yml.bak")),
                        "the previous content is preserved before any rewrite"),
                () -> assertEquals("check-interval-seconds: 42\n",
                        Files.readString(directory.resolve("payments.yml.bak")))
        );
    }

    @Test
    @DisplayName("a whole-number value stays whole, it is not rewritten as 10.0")
    void wholeNumbersStayWhole() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        // Gson's default number policy reads every number back as a Double, which turns
        // `check-interval-seconds: 10` into `10.0` on the next write. DisplayTags had to know
        // this and set ToNumberPolicy.LONG_OR_DOUBLE itself; ConfigLoader does it for everyone.
        final String content = Files.readString(file());
        assertAll(
                () -> assertTrue(content.contains("check-interval-seconds: 10"), content),
                () -> assertFalse(content.contains("10.0"), content)
        );
    }

    // ---------------------------------------------------------------- comment round-trip

    @Test
    @DisplayName("round-trip: a reworded comment and a new setting reach an existing file, values survive")
    void commentsAndNewSettingsReachAnExistingFile() throws Exception {
        // A file written against an older version of the spec: correct keys, old comment, and
        // one section missing entirely.
        Files.writeString(file(), """
                # An outdated comment nobody rewrote
                check-interval-seconds: 99
                confirmation-channel-id: '555'
                """);

        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final String content = Files.readString(file());
        assertAll(
                () -> assertEquals(99L, handle.get().checkIntervalSeconds(), "operator value kept"),
                () -> assertEquals("555", handle.get().confirmationChannelId(), "operator value kept"),
                () -> assertFalse(content.contains("An outdated comment nobody rewrote"),
                        "the stale comment is replaced"),
                () -> assertTrue(content.contains("# How often the account is polled, in seconds."),
                        "the current comment is written"),
                () -> assertTrue(content.contains("# Test configuration"), "the header is written"),
                () -> assertTrue(content.contains("channel-id: '1417574134958788720'"),
                        "a section added to the spec since reaches the file with its default")
        );
    }

    @Test
    @DisplayName("round-trip: the header is emitted once, not duplicated on every load")
    void headerIsNotDuplicated() throws Exception {
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        ConfigLoader.builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final long headers = Files.readAllLines(file()).stream()
                .filter(line -> line.equals("# Test configuration"))
                .count();
        assertEquals(1, headers, "the header must not accumulate");
    }

    // ---------------------------------------------------------------- validation

    @Test
    @DisplayName("validation: a nonsensical value is rejected at load time, not in production")
    void validationRejectsBadValues() throws Exception {
        Files.writeString(file(), """
                check-interval-seconds: -5
                """);

        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay()
                        .validator(config -> {
                            if (config.checkIntervalSeconds() <= 0) {
                                throw new IllegalArgumentException(
                                        "check-interval-seconds must be positive, was " + config.checkIntervalSeconds());
                            }
                        })
                        .load());

        assertAll(
                () -> assertTrue(error.getMessage().contains("must be positive")),
                () -> assertTrue(error.getMessage().contains("payments.yml"), "the message names the file")
        );
    }

    @Test
    @DisplayName("validation also covers a value that came from the environment")
    void validationCoversEnvironmentValues() throws Exception {
        final ConfigValidationException error = assertThrows(ConfigValidationException.class,
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

    // ---------------------------------------------------------------- broken file

    @Test
    @DisplayName("a truncated file is reported, not silently replaced by defaults")
    void brokenFileIsReported() throws Exception {
        Files.writeString(file(), "check-interval-seconds: 10\n  : : oops\n\tbad tab");

        assertThrows(ConfigReadException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay().load());
    }

    @Test
    @DisplayName("a YAML document that is not a mapping names the file instead of throwing a raw CCE")
    void nonMappingRootIsReported() throws Exception {
        Files.writeString(file(), "- just\n- a\n- list\n");

        final ConfigReadException error = assertThrows(ConfigReadException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay().load());

        assertTrue(error.getMessage().contains("payments.yml"));
    }

    @Test
    @DisplayName("the YAML loader does not instantiate arbitrary classes named in the file")
    void yamlLoaderIsSafe() throws Exception {
        // A bare `new Yaml()` honours explicit tags and would try to construct this type.
        Files.writeString(file(), """
                check-interval-seconds: !!java.net.URLClassLoader [[]]
                """);

        assertThrows(ConfigReadException.class,
                () -> ConfigLoader.builder(file(), TestSpecs.Payments.class)
                        .withoutEnvironmentOverlay().load());
    }

    // ---------------------------------------------------------------- reload

    @Test
    @DisplayName("reload picks up an edited file through the same stable instance")
    void reloadPicksUpChanges() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        final TestSpecs.Payments config = handle.get();

        assertEquals(10L, config.checkIntervalSeconds());

        Files.writeString(file(), Files.readString(file())
                .replace("check-interval-seconds: 10", "check-interval-seconds: 77"));
        handle.reload();

        assertEquals(77L, config.checkIntervalSeconds(),
                "the instance handed out earlier must see the new value");
    }

    @Test
    @DisplayName("the spec's own @Reload method reloads through the handle")
    void specReloadAnnotationWorks() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        final TestSpecs.Payments config = handle.get();

        Files.writeString(file(), Files.readString(file())
                .replace("check-interval-seconds: 10", "check-interval-seconds: 33"));
        config.reload();

        assertEquals(33L, config.checkIntervalSeconds());
    }

    @Test
    @DisplayName("a reload that fails validation leaves the previously loaded values in place")
    void failedValidationOnReloadKeepsOldValues() throws Exception {
        final ConfigValidator<TestSpecs.Payments> validator = config -> {
            if (config.checkIntervalSeconds() <= 0) {
                throw new IllegalArgumentException("check-interval-seconds must be positive");
            }
        };
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class)
                .withoutEnvironmentOverlay().validator(validator).load();
        final TestSpecs.Payments config = handle.get();

        Files.writeString(file(), Files.readString(file())
                .replace("check-interval-seconds: 10", "check-interval-seconds: -1"));

        assertThrows(ConfigValidationException.class, handle::reload);
        assertEquals(10L, config.checkIntervalSeconds(),
                "values the application rejected must never become visible");
    }

    @Test
    @DisplayName("a failed reload leaves the previously loaded values in place")
    void failedReloadKeepsOldValues() throws Exception {
        final ConfigHandle<TestSpecs.Payments> handle = ConfigLoader
                .builder(file(), TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        final TestSpecs.Payments config = handle.get();

        Files.writeString(file(), Files.readString(file())
                .replace("check-interval-seconds:", "check-interval-secondz:"));

        assertThrows(UnknownConfigKeyException.class, handle::reload);
        assertEquals(10L, config.checkIntervalSeconds(),
                "a rejected reload must not leave the config half-applied");
    }
}
