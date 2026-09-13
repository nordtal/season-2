package eu.nordtal.s2.steward.ui.configfile;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing one value back without disturbing anything else.
 *
 * <p>The assertions are on the <b>whole text of the file</b>, not on a re-parse. A parse-level
 * check would pass just as happily for a file that had been dumped back out with every comment
 * gone - which is the exact failure this class exists to prevent, since those comments are the
 * only documentation an operator editing another container's config has.</p>
 */
class ConfigFilesWriteTest {

    @TempDir
    Path directory;

    private Path fixture;

    @BeforeEach
    void writeFixtureWithJcore() throws ConfigException {
        fixture = directory.resolve("fixture.yml");
        ConfigLoader.builder(fixture, FixtureSpec.class).load();
    }

    @Test
    void changingOneValueLeavesEveryOtherByteWhereItWas() throws IOException {
        final String before = Files.readString(fixture);

        ConfigFiles.write(fixture, Map.of("port", "9090"));

        assertEquals(before.replace("port: 8080", "port: 9090"), Files.readString(fixture));
    }

    @Test
    void changingSeveralValuesAtOnceLeavesEveryOtherByteWhereItWas() throws IOException {
        final String before = Files.readString(fixture);

        ConfigFiles.write(fixture, Map.of(
                "enabled", "false",
                "worker.base-url", "http://steward-worker:9999",
                "worker.limits.max-retries", "10"));

        assertEquals(before
                        .replace("enabled: true", "enabled: false")
                        .replace("base-url: http://steward-worker:8082",
                                "base-url: http://steward-worker:9999")
                        .replace("max-retries: 3", "max-retries: 10"),
                Files.readString(fixture));
    }

    @Test
    void theInterfacesOwnConfigSurvivesAnEditWordForWord() throws IOException, ConfigException {
        final Path file = directory.resolve("steward-ui.yml");
        ConfigLoader.builder(file, UiSpec.class).load();
        final String before = Files.readString(file);

        ConfigFiles.write(file, Map.of("session-hours", "24"));

        assertEquals(before.replace("session-hours: 12", "session-hours: 24"),
                Files.readString(file));
    }

    @Test
    void writeReturnsTheFileAsItNowReads() throws IOException {
        final ConfigDocument document = ConfigFiles.write(fixture, Map.of("port", "9090"));

        assertEquals("9090", document.find("port").orElseThrow().value());
        assertEquals(List.of("A whole number.", "", "With a blank line in the middle of its comment."),
                document.find("port").orElseThrow().comments());
    }

    @Test
    void noChangesRewritesNothing() throws IOException {
        final String before = Files.readString(fixture);

        ConfigFiles.write(fixture, Map.of());

        assertEquals(before, Files.readString(fixture));
    }

    // ---------------------------------------------------------------------------------------
    // Quoting
    // ---------------------------------------------------------------------------------------

    @Test
    void aValueThatNeedsNoQuotesGetsNone() throws IOException {
        ConfigFiles.write(fixture, Map.of("public-url", "https://steward.nordtal.eu/path"));

        assertTrue(Files.readString(fixture).contains(
                "\npublic-url: https://steward.nordtal.eu/path\n"), Files.readString(fixture));
    }

    @Test
    void aStringThatYamlWouldReadAsSomethingElseIsQuoted() throws IOException {
        assertRendersAs("12", "'12'");
        assertRendersAs("1.5", "'1.5'");
        assertRendersAs("true", "'true'");
        assertRendersAs("No", "'No'");
        assertRendersAs("null", "'null'");
        assertRendersAs("~", "'~'");
        assertRendersAs("", "''");
        assertRendersAs("2026-09-13", "'2026-09-13'");
    }

    @Test
    void aStringWithSyntaxInItIsQuoted() throws IOException {
        assertRendersAs("a: b", "'a: b'");
        assertRendersAs("*star", "'*star'");
        assertRendersAs("&anchor", "'&anchor'");
        assertRendersAs("!tag", "'!tag'");
        assertRendersAs("{brace}", "'{brace}'");
        assertRendersAs("[bracket]", "'[bracket]'");
        assertRendersAs("# hash", "'# hash'");
        assertRendersAs("trailing ", "'trailing '");
        // A quote only means something at the start of a scalar, so this one needs nothing.
        assertRendersAs("it's fine", "it's fine");
        assertRendersAs("'quoted'", "'''quoted'''");
    }

    @Test
    void aValueWithAControlCharacterInItIsAlwaysEscaped() throws IOException {
        assertRendersAs("line one\nline two", "\"line one\\nline two\"");
        // A raw tab reads back correctly and is escaped anyway: YAML forbids a tab in
        // indentation, and the next person to open this file by hand cannot see one.
        assertRendersAs("tab\there", "\"tab\\there\"");
    }

    @Test
    void aQuotedValueStillReadsBackAsTheStringThatWasWritten() throws IOException {
        for (final String value : List.of("12", "true", "", "a: b", "it's fine", "line one\nline two")) {
            ConfigFiles.write(fixture, Map.of("public-url", value));

            assertEquals(value, ConfigFiles.read(fixture).find("public-url").orElseThrow().value(),
                    "round trip of «" + value + "»");
        }
    }

    @Test
    void aNumberIsWrittenAsItWasTypedAndNotReformatted() throws IOException {
        ConfigFiles.write(fixture, Map.of("ratio", "1.50"));

        assertTrue(Files.readString(fixture).contains("\nratio: 1.50\n"), Files.readString(fixture));
    }

    // ---------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------

    @Test
    void anUnknownPathIsRefusedAndNamed() throws IOException {
        final String before = Files.readString(fixture);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("worker.base-urls", "x")));

        assertTrue(thrown.getMessage().contains("worker.base-urls"), thrown.getMessage());
        assertEquals(before, Files.readString(fixture), "a refused write must not touch the file");
    }

    @Test
    void aListIsRefused() throws IOException {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("stop-services", "smp")));

        assertTrue(thrown.getMessage().contains("stop-services"), thrown.getMessage());
        // Both halves matter. Without "is a list" this passed with the check deleted, because a
        // block sequence also spans several lines and the other refusal caught it instead.
        assertTrue(thrown.getMessage().contains("is a list"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lists and nested sections are not editable in this alpha"),
                thrown.getMessage());
    }

    @Test
    void aListThatFitsOnOneLineIsRefusedToo() throws IOException {
        // `empty-list: []` is on a single line, so this refusal can only come from the kind.
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("empty-list", "smp")));

        assertTrue(thrown.getMessage().contains("empty-list"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("is a list"), thrown.getMessage());
    }

    @Test
    void aNestedSectionIsRefused() throws IOException {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("worker", "anything")));

        assertTrue(thrown.getMessage().contains("worker"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("is a nested section"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("lists and nested sections are not editable in this alpha"),
                thrown.getMessage());
    }

    @Test
    void aValueOfTheWrongTypeIsRefused() throws IOException {
        final String before = Files.readString(fixture);

        final IllegalArgumentException boolish = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("enabled", "maybe")));
        assertTrue(boolish.getMessage().contains("enabled"), boolish.getMessage());
        assertTrue(boolish.getMessage().contains("maybe"), boolish.getMessage());

        final IllegalArgumentException intish = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", "12x")));
        assertTrue(intish.getMessage().contains("port"), intish.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", "1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("ratio", "quite a lot")));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("ratio", ".nan")));

        assertEquals(before, Files.readString(fixture), "a refused write must not touch the file");
    }

    @Test
    void aMultiLineValueIsRefused() throws IOException {
        final Path file = directory.resolve("block.yml");
        Files.writeString(file, """
                motd: |-
                  line one
                  line two
                """);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("motd", "one line")));

        assertTrue(thrown.getMessage().contains("motd"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("written across several lines"), thrown.getMessage());
    }

    // ---------------------------------------------------------------------------------------
    // The shape of the file
    // ---------------------------------------------------------------------------------------

    @Test
    void aTrailingCommentOnTheSameLineStays() throws IOException {
        final Path file = directory.resolve("trailing.yml");
        Files.writeString(file, "port: 8080 # the one Caddy talks to\n");

        ConfigFiles.write(file, Map.of("port", "9090"));

        assertEquals("port: 9090 # the one Caddy talks to\n", Files.readString(file));
    }

    @Test
    void aKeyWithNoValueGetsOneWithASpaceInFrontOfIt() throws IOException {
        final Path file = directory.resolve("empty-value.yml");
        Files.writeString(file, """
                token:
                port: 8080
                """);

        ConfigFiles.write(file, Map.of("token", "abcd"));

        assertEquals("""
                token: abcd
                port: 8080
                """, Files.readString(file));
    }

    @Test
    void windowsLineEndingsSurviveTheEdit() throws IOException {
        final Path file = directory.resolve("crlf.yml");
        Files.writeString(file, "# a comment\r\nport: 8080\r\nname: smp\r\n");

        ConfigFiles.write(file, Map.of("port", "9090"));

        assertEquals("# a comment\r\nport: 9090\r\nname: smp\r\n", Files.readString(file));
    }

    @Test
    void aFileWithNoNewlineAtItsEndKeepsNotHavingOne() throws IOException {
        final Path file = directory.resolve("no-eol.yml");
        Files.writeString(file, "port: 8080");

        ConfigFiles.write(file, Map.of("port", "9090"));

        assertEquals("port: 9090", Files.readString(file));
    }

    @Test
    void nonAsciiInACommentIsNotMangled() throws IOException {
        final Path file = directory.resolve("utf8.yml");
        Files.writeString(file, """
                # Die Größe der Welt - §8a, „Steward"
                size: 4096
                """, StandardCharsets.UTF_8);
        final String before = Files.readString(file, StandardCharsets.UTF_8);

        ConfigFiles.write(file, Map.of("size", "8192"));

        assertEquals(before.replace("size: 4096", "size: 8192"),
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void theWriteLeavesNoTemporaryFileBehind() throws IOException {
        ConfigFiles.write(fixture, Map.of("port", "9090"));

        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of("fixture.yml"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    /** Writes {@code value} into a string key and asserts the exact characters that land in the file. */
    private void assertRendersAs(final String value, final String expected) throws IOException {
        ConfigFiles.write(fixture, Map.of("public-url", value));

        final String line = Files.readAllLines(fixture).stream()
                .filter(l -> l.startsWith("public-url:"))
                .findFirst()
                .orElseThrow();
        assertEquals("public-url: " + expected, line, "writing «" + value + "»");
    }
}
