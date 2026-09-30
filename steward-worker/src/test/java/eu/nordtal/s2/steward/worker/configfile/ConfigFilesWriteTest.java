package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writing one value back without disturbing anything else, asserted on the whole text of the file.
 *
 * A re-parse would pass a file dumped back out with every comment gone.
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

        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")));

        assertEquals(before.replace("port: 8080", "port: 9090"), Files.readString(fixture));
    }

    @Test
    void changingSeveralValuesAtOnceLeavesEveryOtherByteWhereItWas() throws IOException {
        final String before = Files.readString(fixture);

        ConfigFiles.write(
                fixture,
                Map.of(
                        "enabled", ConfigChange.of("false"),
                        "worker.base-url", ConfigChange.of("http://steward-worker:9999"),
                        "worker.limits.max-retries", ConfigChange.of("10")));

        assertEquals(
                before.replace("enabled: true", "enabled: false")
                        .replace("base-url: http://steward-worker:8082", "base-url: http://steward-worker:9999")
                        .replace("max-retries: 3", "max-retries: 10"),
                Files.readString(fixture));
    }

    @Test
    void thisServicesOwnConfigSurvivesAnEditWordForWord() throws IOException, ConfigException {
        // The file is not the point: a real spec written by jcore is, since untouched content must come back exactly.
        final Path file = directory.resolve("steward.yml");
        ConfigLoader.builder(file, StewardSpec.class).load();
        final String before = Files.readString(file);

        ConfigFiles.write(file, Map.of("download-timeout-seconds", ConfigChange.of("900")));

        // Anchored to the start of a line: a bare replace would rewrite any comment that quotes the same text too.
        assertEquals(
                before.replace("\ndownload-timeout-seconds: 600\n", "\ndownload-timeout-seconds: 900\n"),
                Files.readString(file));
    }

    @Test
    void writeReturnsTheFileAsItNowReads() throws IOException {
        final ConfigDocument document = ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")));

        assertEquals("9090", document.find("port").orElseThrow().value());
        // jcore writes no comments at all; the explanation once carried by @Comment now lives only in the schema.
        assertEquals(List.of(), document.find("port").orElseThrow().comments());
    }

    @Test
    void noChangesRewritesNothing() throws IOException {
        final String before = Files.readString(fixture);

        ConfigFiles.write(fixture, Map.of());

        assertEquals(before, Files.readString(fixture));
    }

    // Quoting

    @Test
    void aValueThatNeedsNoQuotesGetsNone() throws IOException {
        ConfigFiles.write(fixture, Map.of("public-url", ConfigChange.of("https://steward.nordtal.eu/path")));

        assertTrue(
                Files.readString(fixture).contains("\npublic-url: https://steward.nordtal.eu/path\n"),
                Files.readString(fixture));
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
        // A raw tab reads back correctly and is escaped anyway: YAML forbids a tab in indentation, hand-edited or not.
        assertRendersAs("tab\there", "\"tab\\there\"");
    }

    @Test
    void aQuotedValueStillReadsBackAsTheStringThatWasWritten() throws IOException {
        for (final String value : List.of("12", "true", "", "a: b", "it's fine", "line one\nline two")) {
            ConfigFiles.write(fixture, Map.of("public-url", ConfigChange.of(value)));

            assertEquals(
                    value,
                    ConfigFiles.read(fixture).find("public-url").orElseThrow().value(),
                    "round trip of «" + value + "»");
        }
    }

    @Test
    void aNumberIsWrittenAsItWasTypedAndNotReformatted() throws IOException {
        ConfigFiles.write(fixture, Map.of("ratio", ConfigChange.of("1.50")));

        assertTrue(Files.readString(fixture).contains("\nratio: 1.50\n"), Files.readString(fixture));
    }

    // Refusals

    @Test
    void anUnknownPathIsRefusedAndNamed() throws IOException {
        final String before = Files.readString(fixture);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("worker.base-urls", ConfigChange.of("x"))));

        assertTrue(thrown.getMessage().contains("worker.base-urls"), thrown.getMessage());
        assertEquals(before, Files.readString(fixture), "a refused write must not touch the file");
    }

    @Test
    void oneValueSentToAListIsRefused() throws IOException {
        final String before = Files.readString(fixture);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("stop-services", ConfigChange.of("smp"))));

        assertTrue(thrown.getMessage().contains("stop-services"), thrown.getMessage());
        // Both halves matter: writing stop-services: smp over a list of three would parse while stopping only one.
        assertTrue(thrown.getMessage().contains("is a list"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("send its entries"), thrown.getMessage());
        assertEquals(before, Files.readString(fixture), "a refused write must not touch the file");
    }

    @Test
    void aListSentToASingleValueIsRefused() throws IOException {
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", ConfigChange.list(List.of("1", "2")))));

        assertTrue(thrown.getMessage().contains("port"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("is a single value"), thrown.getMessage());
    }

    @Test
    void aNestedSectionIsRefused() throws IOException {
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("worker", ConfigChange.of("anything"))));

        assertTrue(thrown.getMessage().contains("worker"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("is a nested section"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("change the keys under it"), thrown.getMessage());
    }

    @Test
    void aValueOfTheWrongTypeIsRefused() throws IOException {
        final String before = Files.readString(fixture);

        final IllegalArgumentException boolish = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("enabled", ConfigChange.of("maybe"))));
        assertTrue(boolish.getMessage().contains("enabled"), boolish.getMessage());
        assertTrue(boolish.getMessage().contains("maybe"), boolish.getMessage());

        final IllegalArgumentException intish = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("12x"))));
        assertTrue(intish.getMessage().contains("port"), intish.getMessage());

        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("1.5"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("ratio", ConfigChange.of("quite a lot"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("ratio", ConfigChange.of(".nan"))));

        assertEquals(before, Files.readString(fixture), "a refused write must not touch the file");
    }

    @Test
    void aNumericKeyCannotBeGivenMoreThanOneLine() throws IOException {
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("8080\n9090"))));

        assertTrue(thrown.getMessage().contains("port"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cannot hold more than one line"), thrown.getMessage());
    }

    // The shape of the file

    @Test
    void aTrailingCommentOnTheSameLineStays() throws IOException {
        final Path file = directory.resolve("trailing.yml");
        Files.writeString(file, "port: 8080 # the one Caddy talks to\n");

        ConfigFiles.write(file, Map.of("port", ConfigChange.of("9090")));

        assertEquals("port: 9090 # the one Caddy talks to\n", Files.readString(file));
    }

    @Test
    void aKeyWithNoValueGetsOneWithASpaceInFrontOfIt() throws IOException {
        final Path file = directory.resolve("empty-value.yml");
        Files.writeString(file, """
                token:
                port: 8080
                """);

        ConfigFiles.write(file, Map.of("token", ConfigChange.of("abcd")));

        assertEquals("""
                token: abcd
                port: 8080
                """, Files.readString(file));
    }

    @Test
    void windowsLineEndingsSurviveTheEdit() throws IOException {
        final Path file = directory.resolve("crlf.yml");
        Files.writeString(file, "# a comment\r\nport: 8080\r\nname: smp\r\n");

        ConfigFiles.write(file, Map.of("port", ConfigChange.of("9090")));

        assertEquals("# a comment\r\nport: 9090\r\nname: smp\r\n", Files.readString(file));
    }

    @Test
    void aFileWithNoNewlineAtItsEndKeepsNotHavingOne() throws IOException {
        final Path file = directory.resolve("no-eol.yml");
        Files.writeString(file, "port: 8080");

        ConfigFiles.write(file, Map.of("port", ConfigChange.of("9090")));

        assertEquals("port: 9090", Files.readString(file));
    }

    @Test
    void nonAsciiInACommentIsNotMangled() throws IOException {
        final Path file = directory.resolve("utf8.yml");
        Files.writeString(file, """
                # The size of the world \u2013 §8a, «Steward»
                size: 4096
                """, StandardCharsets.UTF_8);
        final String before = Files.readString(file, StandardCharsets.UTF_8);

        ConfigFiles.write(file, Map.of("size", ConfigChange.of("8192")));

        assertEquals(before.replace("size: 4096", "size: 8192"), Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void theWriteLeavesNoTemporaryFileBehind() throws IOException {
        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")));

        // fixture.schema.json is jcore's own; it must exist beside fixture.yml for this assertion to mean anything.
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(
                    List.of("fixture.schema.json", "fixture.yml"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    // Values written as a block

    @Test
    void aBlockScalarIsRewrittenAndEverythingAroundItStays() throws IOException {
        final Path file = directory.resolve("block.yml");
        Files.writeString(file, """
                # The greeting.
                motd: |-
                  line one
                  line two
                port: 25565
                """);

        ConfigFiles.write(file, Map.of("motd", ConfigChange.of("one\ntwo\nthree")));

        assertEquals("""
                # The greeting.
                motd: |-
                  one
                  two
                  three
                port: 25565
                """, Files.readString(file));
    }

    @Test
    void aBlockScalarKeepsTheCommentOnItsOwnLine() throws IOException {
        final Path file = directory.resolve("block.yml");
        Files.writeString(file, """
                motd: |- # shown on the server list
                  old
                port: 1
                """);

        ConfigFiles.write(file, Map.of("motd", ConfigChange.of("new\nlines")));

        assertEquals("""
                motd: |- # shown on the server list
                  new
                  lines
                port: 1
                """, Files.readString(file));
    }

    @Test
    void aValueGivenNewlinesBecomesABlock() throws IOException {
        ConfigFiles.write(fixture, Map.of("public-url", ConfigChange.of("one\ntwo")));

        assertEquals(
                "one\ntwo",
                ConfigFiles.read(fixture).find("public-url").orElseThrow().value());
        assertTrue(Files.readString(fixture).contains("public-url: |-\n  one\n  two\n"), Files.readString(fixture));
    }

    @Test
    void aBlockCollapsedOntoOneLineLosesTheBlock() throws IOException {
        final Path file = directory.resolve("block.yml");
        Files.writeString(file, """
                motd: |-
                  line one
                  line two
                port: 1
                """);

        ConfigFiles.write(file, Map.of("motd", ConfigChange.of("one line")));

        assertEquals("""
                motd: one line
                port: 1
                """, Files.readString(file));
    }

    @Test
    void aTrailingNewlineDecidesTheChompingIndicator() throws IOException {
        for (final String value : List.of("a\nb", "a\nb\n", "a\nb\n\n", " leading\nspace")) {
            ConfigFiles.write(fixture, Map.of("public-url", ConfigChange.of(value)));

            assertEquals(
                    value,
                    ConfigFiles.read(fixture).find("public-url").orElseThrow().value(),
                    "round trip of «" + value.replace("\n", "\\n") + "»");
        }
    }

    @Test
    void aBlockThatWouldNotReadBackIsQuotedInstead() throws IOException {
        // Nothing but newlines cannot be a block: the content is empty and the chomping indicator has nothing to chomp.
        ConfigFiles.write(fixture, Map.of("public-url", ConfigChange.of("\n\n")));

        assertEquals(
                "\n\n",
                ConfigFiles.read(fixture).find("public-url").orElseThrow().value());
    }

    // Lists

    @Test
    void aBlockListIsRewrittenInPlace() throws IOException {
        final Path file = directory.resolve("list.yml");
        Files.writeString(file, """
                # What to stop first.
                stop-services:
                  - smp
                  - limbo
                port: 1
                """);

        ConfigFiles.write(file, Map.of("stop-services", ConfigChange.list(List.of("smp", "limbo", "hunger-games"))));

        assertEquals("""
                # What to stop first.
                stop-services:
                  - smp
                  - limbo
                  - hunger-games
                port: 1
                """, Files.readString(file));
    }

    @Test
    void aBlockListEmptiedSaysSoOutLoud() throws IOException {
        final Path file = directory.resolve("list.yml");
        Files.writeString(file, """
                stop-services:
                  - smp
                port: 1
                """);

        ConfigFiles.write(file, Map.of("stop-services", ConfigChange.list(List.of())));

        // [] and a bare stop-services: are different configs: one is an empty list, the other is null.
        assertEquals("""
                stop-services: []
                port: 1
                """, Files.readString(file));
    }

    @Test
    void aFlowListStaysAFlowList() throws IOException {
        final Path file = directory.resolve("list.yml");
        Files.writeString(file, """
                stop-services: [smp, limbo] # in this order
                port: 1
                """);

        ConfigFiles.write(file, Map.of("stop-services", ConfigChange.list(List.of("smp"))));

        assertEquals("""
                stop-services: [smp] # in this order
                port: 1
                """, Files.readString(file));
    }

    @Test
    void anEmptyListCanBeFilled() throws IOException {
        ConfigFiles.write(fixture, Map.of("empty-list", ConfigChange.list(List.of("a", "b"))));

        assertEquals(
                List.of("a", "b"),
                ConfigFiles.read(fixture).find("empty-list").orElseThrow().items());
    }

    @Test
    void aListOfNumbersStaysAListOfNumbers() throws IOException {
        final Path file = directory.resolve("ports.yml");
        Files.writeString(file, """
                ports:
                  - 25565
                  - 25566
                """);

        ConfigFiles.write(file, Map.of("ports", ConfigChange.list(List.of("25565", "19132"))));

        // Not '19132' as a string: a list of ints turning into strings is a config that fails to load.
        assertEquals("""
                ports:
                  - 25565
                  - 19132
                """, Files.readString(file));
    }

    @Test
    void anEntryWithACommaInItStaysOneEntry() throws IOException {
        final Path file = directory.resolve("list.yml");
        Files.writeString(file, """
                names: [a]
                """);

        ConfigFiles.write(file, Map.of("names", ConfigChange.list(List.of("one, two"))));

        assertEquals(
                List.of("one, two"),
                ConfigFiles.read(file).find("names").orElseThrow().items());
    }

    @Test
    void anEntryThatLooksLikeSyntaxIsQuoted() throws IOException {
        for (final String entry : List.of("- dash", "a: b", "12", "true", "", "# hash", "[x]")) {
            ConfigFiles.write(fixture, Map.of("stop-services", ConfigChange.list(List.of(entry, "after"))));

            assertEquals(
                    List.of(entry, "after"),
                    ConfigFiles.read(fixture)
                            .find("stop-services")
                            .orElseThrow()
                            .items(),
                    "round trip of «" + entry + "»");
        }
    }

    @Test
    void aListOfSectionsIsLeftAlone() throws IOException {
        final Path file = directory.resolve("sections.yml");
        Files.writeString(file, """
                servers:
                  - name: smp
                    port: 1
                """);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("servers", ConfigChange.list(List.of("x")))));

        assertTrue(thrown.getMessage().contains("servers"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("list of sections"), thrown.getMessage());
    }

    // Several blocks at once

    @Test
    void anEditThatChangesTheLineCountDoesNotMoveTheOnesBelowIt() throws IOException {
        final Path file = directory.resolve("both.yml");
        Files.writeString(file, """
                motd: |-
                  one
                stop-services:
                  - smp
                  - limbo
                port: 25565
                """);

        // The top block grows by two lines and the list below shrinks by one; edits must not land on wrong lines.
        ConfigFiles.write(
                file,
                Map.of(
                        "motd", ConfigChange.of("one\ntwo\nthree"),
                        "stop-services", ConfigChange.list(List.of("smp")),
                        "port", ConfigChange.of("25566")));

        assertEquals("""
                motd: |-
                  one
                  two
                  three
                stop-services:
                  - smp
                port: 25566
                """, Files.readString(file));
    }

    @Test
    void aNestedListIsIndentedWhereItWas() throws IOException {
        final Path file = directory.resolve("nested.yml");
        Files.writeString(file, """
                backup:
                  # What to stop.
                  stop-services:
                    - smp
                  # How long to wait.
                  timeout: 60
                """);

        ConfigFiles.write(file, Map.of("backup.stop-services", ConfigChange.list(List.of("smp", "limbo"))));

        assertEquals("""
                backup:
                  # What to stop.
                  stop-services:
                    - smp
                    - limbo
                  # How long to wait.
                  timeout: 60
                """, Files.readString(file));
    }

    @Test
    void aFileWithCrlfEndingsKeepsThem() throws IOException {
        final Path file = directory.resolve("crlf.yml");
        Files.writeString(file, "motd: |-\r\n  one\r\nport: 1\r\n");

        ConfigFiles.write(file, Map.of("motd", ConfigChange.of("one\ntwo")));

        assertEquals("motd: |-\r\n  one\r\n  two\r\nport: 1\r\n", Files.readString(file));
    }

    /** Writes {@code value} into a string key and asserts the exact characters that land in the file. */
    private void assertRendersAs(final String value, final String expected) throws IOException {
        ConfigFiles.write(fixture, Map.of("public-url", ConfigChange.of(value)));

        final String line = Files.readAllLines(fixture).stream()
                .filter(l -> l.startsWith("public-url:"))
                .findFirst()
                .orElseThrow();
        assertEquals("public-url: " + expected, line, "writing «" + value + "»");
    }

    /** A save keeps the file's permissions instead of the temp file's owner-only ones. */
    @Test
    void savingLeavesTheFilesOwnPermissionsAlone() throws IOException {
        Assumptions.assumeTrue(Files.getFileStore(fixture).supportsFileAttributeView(PosixFileAttributeView.class));
        Files.setPosixFilePermissions(fixture, PosixFilePermissions.fromString("rw-r--r--"));

        ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("9090")));

        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture)));
    }

    /**
     * {@code 8080 # oops} is a typo, and a typo is a 400.
     *
     * YAML reads it as 8080 with a comment, so the save must refuse it as the operator's mistake, not as a bug.
     */
    @Test
    void aNumberWithSomethingAfterItIsTheOperatorsMistakeAndNotThisPrograms() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(fixture, Map.of("port", ConfigChange.of("8080 # oops"))));
    }
}
