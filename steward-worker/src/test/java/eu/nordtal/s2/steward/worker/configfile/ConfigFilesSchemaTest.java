package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SettingKind;
import eu.nordtal.jcore.config.schema.SettingType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * steward/55: a {@code <name>.schema.json} beside a config file is the first choice for a key's
 * plain-language name, its short explanation, its allowed values and its secrecy - the mechanical
 * {@link Labels#of(String)} and the file's own comments are the second choice, for a file with no
 * schema.
 *
 * <p>The schema fixtures here are {@link SchemaNode} trees built by hand and written out with a
 * plain {@link Gson}, not JSON typed into a text block: {@link Schemas} has to read exactly the
 * shape {@code eu.nordtal.jcore.config.schema.SchemaWriter} writes, and jcore's own record is that
 * shape, so building one and serialising it is the only way to test against it that cannot drift
 * from a guess at what the JSON looks like.</p>
 */
class ConfigFilesSchemaTest {

    @TempDir
    Path directory;

    private static final Gson GSON = new Gson();

    // ---------------------------------------------------------------------------------------
    // The label
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a schema's label wins over the mechanical one Labels.of would have produced")
    void aSchemaLabelWinsOverTheMechanicalOne() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");
        writeSchema("service.yml", Map.of("port", scalar("TCP port", "", false, false, SettingType.INTEGER, null)));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("service.yml"));

        // Labels.of("port") would have said "Port" - both names appear here on purpose, so a
        // failure that comes back after steward/55 is not implemented yet says which one won.
        assertEquals(
                "TCP port",
                entry(document, "port").label(),
                "expected the schema's label \"TCP port\", not the mechanical \"" + Labels.of("port") + "\"");
    }

    // ---------------------------------------------------------------------------------------
    // The explanation, and "no explanation needed"
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the short @Explain text comes from the schema, not the (empty) file comment")
    void explanationComesFromTheSchema() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "port",
                        scalar(
                                "Port",
                                "The TCP port this service listens on.",
                                false,
                                false,
                                SettingType.INTEGER,
                                null)));

        final ConfigEntry port = entry(ConfigFiles.read(directory.resolve("service.yml")), "port");

        assertEquals("The TCP port this service listens on.", port.explanation());
        assertFalse(port.noExplanationNeeded());
    }

    @Test
    @DisplayName("\"no explanation needed\" is carried as its own flag, not as an empty string that looks the same")
    void noExplanationNeededIsItsOwnFlag() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "internal-id: abc\n");
        writeSchema(
                "service.yml", Map.of("internal-id", scalar("Internal id", "", true, false, SettingType.STRING, null)));

        final ConfigEntry entry = entry(ConfigFiles.read(directory.resolve("service.yml")), "internal-id");

        assertEquals("", entry.explanation());
        assertTrue(entry.noExplanationNeeded());
    }

    @Test
    @DisplayName("a key with no schema entry gets an empty explanation and noExplanationNeeded = false")
    void aKeyWithNoSchemaGetsNoExplanation() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");
        // No schema.json at all for this file.

        final ConfigEntry port = entry(ConfigFiles.read(directory.resolve("service.yml")), "port");

        assertEquals("", port.explanation());
        assertFalse(port.noExplanationNeeded());
    }

    // ---------------------------------------------------------------------------------------
    // Allowed values: strict or a suggestion
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("strict allowed values become a closed list")
    void strictAllowedValuesBecomeAClosedList() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "region: eu\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "region",
                        scalar(
                                "Region",
                                "",
                                false,
                                false,
                                SettingType.STRING,
                                new SchemaNode.Choices(List.of("eu", "us"), true))));

        final ConfigEntry.Choices choices = entry(ConfigFiles.read(directory.resolve("service.yml")), "region")
                .choices();

        assertEquals(List.of("eu", "us"), choices.values());
        assertTrue(choices.strict());
    }

    @Test
    @DisplayName("non-strict allowed values are a suggestion, free text still allowed")
    void suggestedAllowedValuesAllowFreeText() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "accent-colour: teal\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "accent-colour",
                        scalar(
                                "Accent colour",
                                "",
                                false,
                                false,
                                SettingType.STRING,
                                new SchemaNode.Choices(List.of("red", "green", "blue"), false))));

        final ConfigEntry.Choices choices = entry(ConfigFiles.read(directory.resolve("service.yml")), "accent-colour")
                .choices();

        assertEquals(List.of("red", "green", "blue"), choices.values());
        assertFalse(choices.strict());
    }

    @Test
    @DisplayName("a key with no @AllowedValues in its schema has no choices at all - not an empty list")
    void noAllowedValuesMeansNullChoices() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");
        writeSchema("service.yml", Map.of("port", scalar("Port", "", false, false, SettingType.INTEGER, null)));

        assertNull(entry(ConfigFiles.read(directory.resolve("service.yml")), "port")
                .choices());
    }

    // ---------------------------------------------------------------------------------------
    // Secret: the schema can only add, never remove, the heuristic's protection
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("the schema's @Secret marks a key secret even though its name matches no heuristic")
    void schemaSecretMarksAKeyTheHeuristicWouldHaveMissed() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "shared-value: abcd\n");
        writeSchema(
                "service.yml",
                Map.of("shared-value", scalar("Shared value", "", false, true, SettingType.STRING, null)));

        assertTrue(entry(ConfigFiles.read(directory.resolve("service.yml")), "shared-value")
                .secret());
    }

    @Test
    @DisplayName("the heuristic still catches a credential-shaped key even when its schema says secret = false")
    void theHeuristicNetStaysUnderASchemaThatSaysNotSecret() throws IOException {
        // This is the state every spec is in until steward/62 retrofits @Secret onto it: jcore
        // 4.0.0 writes a schema for every declared setting regardless of whether it carries
        // @Secret, so "no @Secret yet" and "explicitly not secret" produce the same schema. The
        // heuristic net - which steward/50 asks to stay underneath the schema rather than be
        // replaced by it - is what keeps that from unmasking every token in the stack the moment
        // this ships.
        Files.writeString(directory.resolve("service.yml"), "api-token: abcd\n");
        writeSchema(
                "service.yml", Map.of("api-token", scalar("Api token", "", false, false, SettingType.STRING, null)));

        assertTrue(
                entry(ConfigFiles.read(directory.resolve("service.yml")), "api-token")
                        .secret(),
                "api-token must stay secret: the schema's secret=false must not overrule the"
                        + " key-name heuristic, only add to it");
    }

    // ---------------------------------------------------------------------------------------
    // "not in schema"
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a key the file has but the schema does not is still delivered, marked as not in the schema")
    void aKeyMissingFromTheSchemaIsMarkedAsSuch() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\nlegacy-flag: true\n");
        // The schema knows "port" but says nothing about "legacy-flag" - a setting steward/50
        // says the file, not the schema, is the truth about.
        writeSchema("service.yml", Map.of("port", scalar("Port", "", false, false, SettingType.INTEGER, null)));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("service.yml"));

        assertTrue(entry(document, "port").inSchema());
        assertFalse(entry(document, "legacy-flag").inSchema());
        // Never hidden, and it still gets the mechanical label - the schema said nothing about it.
        assertEquals("Legacy flag", entry(document, "legacy-flag").label());
    }

    @Test
    @DisplayName("a file with no schema at all marks every key in it, not one of them")
    void aFileWithNoSchemaMarksNothing() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");

        assertTrue(entry(ConfigFiles.read(directory.resolve("service.yml")), "port")
                .inSchema());
    }

    @Test
    @DisplayName("extra schema keys the file does not have are ignored, not invented as phantom entries")
    void extraSchemaKeysAreIgnored() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "port", scalar("Port", "", false, false, SettingType.INTEGER, null),
                        "retired-setting", scalar("Retired setting", "", false, false, SettingType.STRING, null)));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("service.yml"));

        assertEquals(
                List.of("port"),
                document.entries().stream().map(ConfigEntry::key).toList());
    }

    // ---------------------------------------------------------------------------------------
    // @Protected: a SECTIONS entry can carry a rule about a specific value (steward/74)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a SECTIONS entry's protectedEntry comes from the schema's @Protected, field and value both")
    void protectedEntryIsReadFromTheSchema() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "languages:\n- tag: en\n- tag: de\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "languages",
                        sections(
                                "Languages",
                                "'en' must be present.",
                                Map.of("tag", scalar("Tag", "", false, false, SettingType.STRING, null)),
                                new SchemaNode.ProtectedEntry("tag", "en"))));

        final ConfigEntry.Protected protectedEntry = entry(
                        ConfigFiles.read(directory.resolve("service.yml")), "languages")
                .protectedEntry();

        assertEquals("tag", protectedEntry.field());
        assertEquals("en", protectedEntry.value());
    }

    @Test
    @DisplayName("a SECTIONS entry whose schema carries no @Protected has a null protectedEntry")
    void protectedEntryIsNullWithoutTheAnnotation() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "tiers:\n- days: 30\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "tiers",
                        sections(
                                "Tiers",
                                "",
                                Map.of("days", scalar("Days", "", false, false, SettingType.INTEGER, null)),
                                null)));

        assertNull(entry(ConfigFiles.read(directory.resolve("service.yml")), "tiers")
                .protectedEntry());
    }

    @Test
    @DisplayName("removing the entry a schema marks @Protected is refused, before the file is touched")
    void removingAProtectedEntryIsRefused() throws IOException {
        final String original = "languages:\n- tag: en\n- tag: de\n";
        Files.writeString(directory.resolve("service.yml"), original);
        // The explanation is deliberately as long and as multi-line as the real one: `languages`
        // in the bot's access.yml carries fifteen lines, and a fixture with a one-line explanation
        // would let the length check below pass without ever being tested.
        writeSchema(
                "service.yml",
                Map.of(
                        "languages",
                        sections(
                                "Languages",
                                "Every language the network speaks. A list, so a third language is an edit here\n"
                                        + "and not a release: add the role and the two channels in Discord, add an\n"
                                        + "entry, add <tag>.properties to every module's messages/ directory,\n"
                                        + "restart.\n\n'en' must be present - it is the fallback everything\n"
                                        + "degrades to, and a missing translation shows up as the message key\n"
                                        + "rather than as nothing at all.",
                                Map.of("tag", scalar("Tag", "", false, false, SettingType.STRING, null)),
                                new SchemaNode.ProtectedEntry("tag", "en"))));

        // "de" removed, "en" kept - this is exactly the shape steward/71 already lets an operator
        // send for an ordinary removal; the only difference is which entry is missing.
        final Map<String, ConfigChange> removeEnglish =
                Map.of("languages", ConfigChange.sections(List.of(Map.of("tag", "de"))));

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(directory.resolve("service.yml"), removeEnglish));
        assertTrue(error.getMessage().contains("en"), error.getMessage());
        // One line, not the schema's prose. The live refusal used to paste the whole explanation -
        // fifteen lines for `languages` - into a message the browser shows in an alert, while the
        // page underneath was already displaying that same text. An error says what happened.
        assertFalse(error.getMessage().contains("\n"), "the refusal has to stay one line: " + error.getMessage());
        assertTrue(error.getMessage().length() < 160, "the refusal has to stay short: " + error.getMessage());

        // Refused BEFORE a single line moves - not written, then rejected on the way back out.
        assertEquals(original, Files.readString(directory.resolve("service.yml")));
    }

    @Test
    @DisplayName(
            "removing an entry @Protected does NOT name still works - the rule names one value, not the whole list")
    void removingAnUnprotectedEntryStillWorks() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "languages:\n- tag: en\n- tag: de\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "languages",
                        sections(
                                "Languages",
                                "'en' must be present.",
                                Map.of("tag", scalar("Tag", "", false, false, SettingType.STRING, null)),
                                new SchemaNode.ProtectedEntry("tag", "en"))));

        // "en" kept, "de" removed.
        final Map<String, ConfigChange> removeGerman =
                Map.of("languages", ConfigChange.sections(List.of(Map.of("tag", "en"))));

        final ConfigDocument written = ConfigFiles.write(directory.resolve("service.yml"), removeGerman);

        assertEquals(
                List.of(Map.of("tag", "en")),
                entry(written, "languages").sections().stream()
                        .map(fields -> Map.of(
                                fields.getFirst().key(), fields.getFirst().value()))
                        .toList());
    }

    // ---------------------------------------------------------------------------------------
    // Nesting: the group is the schema's own nesting, same as the file's
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a nested section's schema label and its children's labels both come from the schema")
    void nestingCarriesSchemaLabelsAtEveryLevel() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "worker:\n  base-url: http://x\n");
        writeSchema(
                "service.yml",
                Map.of(
                        "worker",
                        new SchemaNode(
                                SettingKind.MAP,
                                "Worker",
                                "",
                                false,
                                false,
                                null,
                                null,
                                Map.of("base-url", scalar("Where it is", "", false, false, SettingType.STRING, null)),
                                null)));

        final ConfigDocument document = ConfigFiles.read(directory.resolve("service.yml"));

        assertEquals("Worker", entry(document, "worker").label());
        assertEquals("Where it is", entry(document, "worker.base-url").label());
    }

    // ---------------------------------------------------------------------------------------
    // Discovery: every text format, never a binary one, and never the schema file itself
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("discover() now finds every text file, not only .yml")
    void discoverFindsEveryTextFormat() throws IOException {
        Files.createDirectories(directory.resolve("smp"));
        Files.writeString(directory.resolve("smp/config.yml"), "port: 8080\n");
        Files.writeString(directory.resolve("smp/README.txt"), "Read me.\n");
        Files.writeString(directory.resolve("smp/spark-config.json"), "{}\n");

        final List<String> found = ConfigFiles.discover(directory).stream()
                .map(ConfigLocation::name)
                .toList();

        assertTrue(found.contains("config.yml"), found.toString());
        assertTrue(found.contains("README.txt"), found.toString());
        assertTrue(found.contains("spark-config.json"), found.toString());
    }

    @Test
    @DisplayName("discover() still never finds a binary file")
    void discoverNeverFindsABinaryFile() throws IOException {
        Files.createDirectories(directory.resolve("smp"));
        Files.write(directory.resolve("smp/plugin.jar"), new byte[] {0x50, 0x4b, 0x03, 0x04, 0, 0, 0});

        assertEquals(List.of(), ConfigFiles.discover(directory));
    }

    @Test
    @DisplayName("discover() never lists a *.schema.json file as a config file of its own")
    void discoverNeverListsTheSchemaFileItself() throws IOException {
        Files.writeString(directory.resolve("service.yml"), "port: 8080\n");
        writeSchema("service.yml", Map.of("port", scalar("Port", "", false, false, SettingType.INTEGER, null)));

        final List<String> found = ConfigFiles.discover(directory).stream()
                .map(ConfigLocation::name)
                .toList();

        assertEquals(List.of("service.yml"), found);
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private static SchemaNode scalar(
            final String label,
            final String explanation,
            final boolean noExplanationNeeded,
            final boolean secret,
            final SettingType type,
            final SchemaNode.Choices choices) {
        return new SchemaNode(
                SettingKind.SCALAR, label, explanation, noExplanationNeeded, secret, type, choices, Map.of(), null);
    }

    /** A {@link SettingKind#LIST} of nested settings - {@code languages} and {@code tiers}' own shape. */
    private static SchemaNode sections(
            final String label,
            final String explanation,
            final Map<String, SchemaNode> elementFields,
            final SchemaNode.ProtectedEntry protectedEntry) {
        return new SchemaNode(
                SettingKind.LIST, label, explanation, false, false, null, null, elementFields, protectedEntry);
    }

    private void writeSchema(final String ymlName, final Map<String, SchemaNode> children) throws IOException {
        final SchemaNode root = new SchemaNode(SettingKind.MAP, "", "", false, false, null, null, children, null);
        final String base = ymlName.endsWith(".yml") ? ymlName.substring(0, ymlName.length() - 4) : ymlName;
        Files.writeString(directory.resolve(base + ".schema.json"), GSON.toJson(root));
    }

    private static ConfigEntry entry(final ConfigDocument document, final String path) {
        return document.find(path)
                .orElseThrow(() -> new AssertionError("no entry " + path + " in "
                        + document.entries().stream().map(ConfigEntry::path).toList()));
    }
}
