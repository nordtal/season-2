package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RawSyntax} - the raw editor's save-time warning, never a refusal (steward/60).
 */
class RawSyntaxTest {

    // -------------------------------------------------------------------------------------------
    // Format detection - by the file's own name, never its content
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("format is decided by the last extension, service directory included")
    void formatFollowsTheExtension() {
        assertEquals(RawSyntax.Format.YAML, RawSyntax.formatOf("steward.yml"));
        assertEquals(RawSyntax.Format.YAML, RawSyntax.formatOf("smp/smp/config.yaml"));
        assertEquals(RawSyntax.Format.JSON, RawSyntax.formatOf("spark/config.json"));
        assertEquals(RawSyntax.Format.TOML, RawSyntax.formatOf("some/plugin/settings.toml"));
        assertEquals(RawSyntax.Format.PROPERTIES, RawSyntax.formatOf("voice-chat/voicechat-server.properties"));
        assertEquals(RawSyntax.Format.TEXT, RawSyntax.formatOf("plugins/README.txt"));
        assertEquals(RawSyntax.Format.TEXT, RawSyntax.formatOf("no-extension-at-all"));
    }

    // -------------------------------------------------------------------------------------------
    // YAML
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("valid YAML gets no warning")
    void validYamlIsSilent() {
        assertEquals(Optional.empty(), RawSyntax.check("a.yml", "key: value\nother: 1\n"));
    }

    @Test
    @DisplayName("an empty file is not a YAML error")
    void emptyYamlIsSilent() {
        assertEquals(Optional.empty(), RawSyntax.check("a.yml", ""));
        assertEquals(Optional.empty(), RawSyntax.check("a.yml", "# just a comment\n"));
    }

    @Test
    @DisplayName("broken YAML names the line it broke on")
    void brokenYamlNamesTheLine() {
        // An unclosed flow sequence keeps SnakeYAML scanning forward looking for the `]`, so the
        // line it actually reports is where that search gave up - line 3, not the line the `[`
        // opened on. This test is here to hold that behaviour, not to assert what would be tidier.
        final String content = "one: 1\ntwo: [unterminated\nthree: 3\n";
        final RawSyntax.Warning warning = RawSyntax.check("a.yml", content).orElseThrow();
        assertEquals(3, warning.line(), warning.sentence());
        assertTrue(warning.sentence().startsWith("Line 3:"), warning.sentence());
    }

    @Test
    @DisplayName("a YAML file whose root is not a mapping is named too")
    void nonMappingRootIsNamed() {
        final RawSyntax.Warning warning =
                RawSyntax.check("a.yml", "- one\n- two\n").orElseThrow();
        assertEquals(1, warning.line());
        assertTrue(warning.sentence().contains("set of keys"), warning.sentence());
    }

    // -------------------------------------------------------------------------------------------
    // JSON
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("valid JSON gets no warning")
    void validJsonIsSilent() {
        assertEquals(Optional.empty(), RawSyntax.check("a.json", "{\"a\": 1, \"b\": [1, 2]}"));
    }

    @Test
    @DisplayName("an empty file is not a JSON error")
    void emptyJsonIsSilent() {
        assertEquals(Optional.empty(), RawSyntax.check("a.json", ""));
        assertEquals(Optional.empty(), RawSyntax.check("a.json", "   \n"));
    }

    @Test
    @DisplayName("broken JSON names the line it broke on")
    void brokenJsonNamesTheLine() {
        final String content = "{\n  \"a\": 1,\n  \"b\": [1, 2,\n}";
        final RawSyntax.Warning warning = RawSyntax.check("a.json", content).orElseThrow();
        assertEquals(4, warning.line(), warning.sentence());
        assertTrue(warning.sentence().startsWith("Line 4:"), warning.sentence());
    }

    // -------------------------------------------------------------------------------------------
    // Properties - one check, not a parser
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ordinary properties text gets no warning")
    void ordinaryPropertiesIsSilent() {
        assertEquals(Optional.empty(), RawSyntax.check("a.properties", "one=1\ntwo=two\n# a comment\nthree: 3\n"));
    }

    @Test
    @DisplayName("a malformed unicode escape names its line")
    void malformedUnicodeEscapeNamesTheLine() {
        final String content = "one=1\ntwo=\\uZZZZ\nthree=3\n";
        final RawSyntax.Warning warning =
                RawSyntax.check("a.properties", content).orElseThrow();
        assertEquals(2, warning.line(), warning.sentence());
        assertTrue(warning.sentence().contains("uXXXX"), warning.sentence());
    }

    // -------------------------------------------------------------------------------------------
    // TOML and plain text - the "too expensive" fallback the ticket names outright
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("TOML is never checked, however broken")
    void tomlIsNeverChecked() {
        assertEquals(Optional.empty(), RawSyntax.check("a.toml", "this is not [[ valid toml at all"));
    }

    @Test
    @DisplayName("plain text is never checked")
    void plainTextIsNeverChecked() {
        assertEquals(Optional.empty(), RawSyntax.check("README.txt", "anything at all\n{{{"));
    }
}
