package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link RawSyntax}, the raw editor's save-time warning, never a refusal. */
class RawSyntaxTest {

    // Format detection, by the file's own name and never its content

    @Test
    void formatIsDecidedByTheLastExtensionServiceDirectoryIncluded() {
        assertEquals(RawSyntax.Format.YAML, RawSyntax.formatOf("steward.yml"));
        assertEquals(RawSyntax.Format.YAML, RawSyntax.formatOf("smp/smp/config.yaml"));
        assertEquals(RawSyntax.Format.JSON, RawSyntax.formatOf("spark/config.json"));
        assertEquals(RawSyntax.Format.TOML, RawSyntax.formatOf("some/plugin/settings.toml"));
        assertEquals(RawSyntax.Format.PROPERTIES, RawSyntax.formatOf("voice-chat/voicechat-server.properties"));
        assertEquals(RawSyntax.Format.TEXT, RawSyntax.formatOf("plugins/README.txt"));
        assertEquals(RawSyntax.Format.TEXT, RawSyntax.formatOf("no-extension-at-all"));
    }

    // YAML

    @Test
    void validYamlGetsNoWarning() {
        assertEquals(Optional.empty(), RawSyntax.check("a.yml", "key: value\nother: 1\n"));
    }

    @Test
    void anEmptyFileIsNotAYamlError() {
        assertEquals(Optional.empty(), RawSyntax.check("a.yml", ""));
        assertEquals(Optional.empty(), RawSyntax.check("a.yml", "# just a comment\n"));
    }

    @Test
    void brokenYamlNamesTheLineItBrokeOn() {
        // An unclosed flow sequence keeps SnakeYAML scanning for `]`; it reports where that search gave up, not `[`.
        final String content = "one: 1\ntwo: [unterminated\nthree: 3\n";
        final RawSyntax.Warning warning = RawSyntax.check("a.yml", content).orElseThrow();
        assertEquals(3, warning.line(), warning.sentence());
        assertTrue(warning.sentence().startsWith("Line 3:"), warning.sentence());
    }

    @Test
    void aYamlFileWhoseRootIsNotAMappingIsNamedToo() {
        final RawSyntax.Warning warning =
                RawSyntax.check("a.yml", "- one\n- two\n").orElseThrow();
        assertEquals(1, warning.line());
        assertTrue(warning.sentence().contains("set of keys"), warning.sentence());
    }

    // JSON

    @Test
    void validJsonGetsNoWarning() {
        assertEquals(Optional.empty(), RawSyntax.check("a.json", "{\"a\": 1, \"b\": [1, 2]}"));
    }

    @Test
    void anEmptyFileIsNotAJsonError() {
        assertEquals(Optional.empty(), RawSyntax.check("a.json", ""));
        assertEquals(Optional.empty(), RawSyntax.check("a.json", "   \n"));
    }

    @Test
    void brokenJsonNamesTheLineItBrokeOn() {
        final String content = "{\n  \"a\": 1,\n  \"b\": [1, 2,\n}";
        final RawSyntax.Warning warning = RawSyntax.check("a.json", content).orElseThrow();
        assertEquals(4, warning.line(), warning.sentence());
        assertTrue(warning.sentence().startsWith("Line 4:"), warning.sentence());
    }

    // Properties: one check, not a parser

    @Test
    void ordinaryPropertiesTextGetsNoWarning() {
        assertEquals(Optional.empty(), RawSyntax.check("a.properties", "one=1\ntwo=two\n# a comment\nthree: 3\n"));
    }

    @Test
    void aMalformedUnicodeEscapeNamesItsLine() {
        final String content = "one=1\ntwo=\\uZZZZ\nthree=3\n";
        final RawSyntax.Warning warning =
                RawSyntax.check("a.properties", content).orElseThrow();
        assertEquals(2, warning.line(), warning.sentence());
        assertTrue(warning.sentence().contains("uXXXX"), warning.sentence());
    }

    // TOML and plain text: no check

    @Test
    void tomlIsNeverCheckedHoweverBroken() {
        assertEquals(Optional.empty(), RawSyntax.check("a.toml", "this is not [[ valid toml at all"));
    }

    @Test
    void plainTextIsNeverChecked() {
        assertEquals(Optional.empty(), RawSyntax.check("README.txt", "anything at all\n{{{"));
    }
}
