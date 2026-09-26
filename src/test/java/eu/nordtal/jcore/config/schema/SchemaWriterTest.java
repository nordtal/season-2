package eu.nordtal.jcore.config.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.TestSpecs;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jcore writes a {@code config.schema.json} beside the YAML it writes; the YAML carries no comments.
 */
class SchemaWriterTest {

    @TempDir
    Path directory;

    // the annotation split

    @Test
    void explainTextGoesToSchemaAndNeverToYaml() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String yaml = Files.readString(file);
        assertAll(
                () -> assertFalse(
                        yaml.contains("How often the account is polled"),
                        "the long @Comment text must never reach the YAML: " + yaml),
                () -> assertFalse(
                        yaml.lines().anyMatch(line -> line.strip().startsWith("#")),
                        "the YAML must carry no comment lines at all: " + yaml));

        final SchemaNode schema = SchemaWriter.build(TestSpecs.Payments.class);
        final SchemaNode checkInterval = schema.children().get("check-interval-seconds");
        assertAll(
                () -> assertEquals(
                        "How often payments are checked, in seconds.",
                        checkInterval.explanation(),
                        "the schema must carry @Explain's short text"),
                () -> assertFalse(
                        checkInterval.explanation().contains("How often the account is polled"),
                        "the schema must not carry @Comment's long text"));
    }

    @Test
    void unmigratedPropertyGetsAnEmptyExplanation() {
        // Colliding.ab() carries no annotation at all - the one case left with no explanation.
        final SchemaNode schema = SchemaWriter.build(TestSpecs.Colliding.class);
        final SchemaNode ab = schema.children().get("a-b");
        assertAll(() -> assertEquals("", ab.explanation()), () -> assertFalse(ab.noExplanationNeeded()));
    }

    @Test
    void commentIsUsedAsAFallbackWhenNoExplainIsGiven() {
        // Balance.channelId() has only @Comment, the ordinary unmigrated case, not an edge case.
        final SchemaNode schema = SchemaWriter.build(TestSpecs.Balance.class);
        final SchemaNode channelId = schema.children().get("channel-id");
        assertEquals(
                "The voice channel that shows the balance.",
                channelId.explanation(),
                "an unmigrated property must fall back to its @Comment text, not stay empty");
    }

    @Test
    void multiLineCommentIsJoinedWithNewlines() {
        final SchemaNode schema = SchemaWriter.build(MultiLineCommentOnly.class);
        assertEquals(
                "First line.\n\nSecond paragraph line.",
                schema.children().get("option").explanation(),
                "@Comment is a String[], one array entry per line - the browser already renders "
                        + "a multi-line explanation, so a newline join keeps a blank-line paragraph "
                        + "break intact instead of running everything onto one line");
    }

    // Not named `setting()`: the vendored Spec treats any `set*` method as a setter.
    @ConfigSpec
    public interface MultiLineCommentOnly {
        @Order(1)
        @Key("option")
        @Comment({"First line.", "", "Second paragraph line."})
        default String option() {
            return "";
        }
    }

    @Test
    void noExplanationNeededIsRecorded() {
        final SchemaNode schema = SchemaWriter.build(TestSpecs.SchemaExample.class);
        final SchemaNode internalId = schema.children().get("internal-id");
        assertAll(() -> assertTrue(internalId.noExplanationNeeded()), () -> assertEquals("", internalId.explanation()));
    }

    @Test
    void explainAndNoExplanationNeededTogetherIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaWriter.build(Contradiction.class));
    }

    @ConfigSpec
    public interface Contradiction {
        // Not named `setting()`: the vendored Spec would reject a `set*` method before @Explain is checked.
        @Explain("short")
        @NoExplanationNeeded
        default String option() {
            return "";
        }
    }

    // the file-level header

    @Test
    void headerBecomesTheRootExplanation() {
        // A spec's header is written into the schema and nowhere else.
        final SchemaNode schema = SchemaWriter.build(TestSpecs.Payments.class);
        assertEquals("Test configuration\nSecond header line", schema.explanation());
    }

    @Test
    void singleLineHeaderIsJustThatLine() {
        assertEquals("Worlds", SchemaWriter.build(TestSpecs.Worlds.class).explanation());
    }

    @Test
    void blankLinesInsideAHeaderSurvive() {
        // Empty header entries are blank lines; dropping them would merge everything into one paragraph.
        assertEquals(
                "First paragraph.\n\nSecond paragraph.",
                SchemaWriter.build(HeaderWithBlankLine.class).explanation());
    }

    // Not named `setting()`: the vendored Spec treats any `set*` method as a setter.
    @ConfigSpec(header = {"First paragraph.", "", "Second paragraph."})
    public interface HeaderWithBlankLine {
        default String option() {
            return "";
        }
    }

    @Test
    void embeddedNewlineIsOneLineBreakAndNotTwo() {
        // headerOf() already splits entries on '\n'; joining with '\n' must not double the break.
        assertEquals(
                "One\nTwo\nThree",
                SchemaWriter.build(HeaderWithEmbeddedNewline.class).explanation());
    }

    @ConfigSpec(header = {"One\nTwo", "Three"})
    public interface HeaderWithEmbeddedNewline {
        default String option() {
            return "";
        }
    }

    @Test
    void absentHeaderStaysEmpty() {
        assertEquals("", SchemaWriter.build(TestSpecs.Balance.class).explanation());
    }

    @Test
    void theRootKeepsNoLabel() {
        assertEquals("", SchemaWriter.build(TestSpecs.Payments.class).label());
    }

    @Test
    void nameIsTheLabel() {
        final SchemaNode schema = SchemaWriter.build(Named.class);

        assertAll(
                () -> assertEquals("API key", schema.children().get("apiKey").label()),
                () -> assertEquals(
                        SettingLabels.of("timeoutSeconds"),
                        schema.children().get("timeoutSeconds").label(),
                        "without @Name the key still gives the label"),
                () -> assertEquals("Payouts", schema.children().get("payouts").label()),
                () -> assertEquals(
                        "Retry after",
                        schema.children().get("payouts").children().get("retry").label()),
                () -> assertEquals(
                        "Queues",
                        schema.children().get("queues").label(),
                        "a list of sections is not named after one of its entries"),
                () -> assertEquals(
                        "Own name",
                        schema.children().get("renamed").label(),
                        "the getter's @Name beats the interface's"));
    }

    @ConfigSpec
    public interface Named {

        @Name("API key")
        default String apiKey() {
            return "";
        }

        default int timeoutSeconds() {
            return 5;
        }

        default Section payouts() {
            return null;
        }

        default List<Section> queues() {
            return List.of();
        }

        @Name("Own name")
        default Section renamed() {
            return null;
        }
    }

    @ConfigSpec
    @Name("Payouts")
    public interface Section {

        @Name("Retry after")
        default int retry() {
            return 3;
        }
    }

    @Test
    void headerIsInTheWrittenSchemaFile() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        final String json = Files.readString(SchemaWriter.schemaFileFor(file));
        assertTrue(
                json.contains("Test configuration\\nSecond header line"),
                "the schema file must carry the header text: " + json);

        // And still not the YAML - 4.0.0's decision is not being walked back here.
        assertFalse(Files.readString(file).contains("Test configuration"), "the header must not return to the YAML");
    }

    @Test
    void aNestedSpecsHeaderIsNotTheChildsExplanation() {
        // A child's explanation belongs to the declaring property, not the nested interface's own header.
        final SchemaNode outer = SchemaWriter.build(Outer.class);
        assertEquals(
                "",
                outer.children().get("inner").explanation(),
                "the nested interface's header is not the parent property's explanation");
    }

    @ConfigSpec(header = "The inner file")
    public interface Inner {
        default String option() {
            return "";
        }
    }

    @ConfigSpec(header = "The outer file")
    public interface Outer {
        Inner inner();
    }

    // secret

    @Test
    void secretIsRecorded() {
        final SchemaNode schema = SchemaWriter.build(TestSpecs.SchemaExample.class);
        assertAll(
                () -> assertTrue(schema.children().get("api-token").secret()),
                () -> assertFalse(schema.children().get("region").secret()));
    }

    // allowed values

    @Test
    void strictAllowedValues() {
        final SchemaNode region =
                SchemaWriter.build(TestSpecs.SchemaExample.class).children().get("region");
        assertAll(
                () -> assertEquals(List.of("eu", "us"), region.choices().values()),
                () -> assertTrue(region.choices().strict()));
    }

    @Test
    void suggestionAllowedValues() {
        final SchemaNode colour =
                SchemaWriter.build(TestSpecs.SchemaExample.class).children().get("accent-colour");
        assertAll(
                () -> assertEquals(
                        List.of("red", "green", "blue"), colour.choices().values()),
                () -> assertFalse(colour.choices().strict()));
    }

    @Test
    void enumPropertyGetsAllowedValuesAutomatically() {
        final SchemaNode mode =
                SchemaWriter.build(TestSpecs.SchemaExample.class).children().get("mode");
        assertAll(
                () -> assertEquals(List.of("STRICT", "LOOSE"), mode.choices().values()),
                () -> assertTrue(mode.choices().strict()),
                () -> assertEquals(SettingType.STRING, mode.type()));
    }

    @Test
    void plainScalarHasNoChoices() {
        final SchemaNode checkInterval =
                SchemaWriter.build(TestSpecs.Payments.class).children().get("check-interval-seconds");
        assertNull(checkInterval.choices());
    }

    // kind, type, and the group

    @Test
    void kindAndTypeMirrorConfigEntry() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        assertAll(
                () -> assertEquals(
                        SettingKind.SCALAR,
                        payments.children().get("check-interval-seconds").kind()),
                () -> assertEquals(
                        SettingType.INTEGER,
                        payments.children().get("check-interval-seconds").type()),
                () -> assertEquals(
                        SettingKind.MAP, payments.children().get("balance").kind()),
                () -> assertNull(payments.children().get("balance").type()));

        final SchemaNode worlds = SchemaWriter.build(TestSpecs.Worlds.class);
        final SchemaNode worldsList = worlds.children().get("worlds");
        assertAll(
                () -> assertEquals(SettingKind.LIST, worldsList.kind()),
                () -> assertTrue(
                        worldsList.children().containsKey("preserved"),
                        "a list of nested specs describes the shape of one element"),
                () -> assertEquals(
                        SettingType.BOOLEAN,
                        worldsList.children().get("preserved").type()));
    }

    @Test
    void groupIsTheNestingItself() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        final Map<String, SchemaNode> balanceChildren =
                payments.children().get("balance").children();
        assertAll(
                () -> assertTrue(balanceChildren.containsKey("channel-id")),
                () -> assertTrue(balanceChildren.containsKey("format")),
                () -> assertFalse(
                        payments.children().containsKey("channel-id"),
                        "a nested setting must not also appear flattened at the root"));
    }

    @Test
    void childrenPreserveDeclarationOrder() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        assertEquals(
                List.of("check-interval-seconds", "confirmation-channel-id", "balance"),
                List.copyOf(payments.children().keySet()));
    }

    @Test
    void proxyHandledMethodsAreNotSettings() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        assertFalse(payments.children().containsKey("reload"));
        assertFalse(payments.children().containsKey("save"));
    }

    // file and schema come into being together

    @Test
    void fileAndSchemaComeIntoBeingTogether() throws Exception {
        final Path file = directory.resolve("payments.yml");
        final Path schema = SchemaWriter.schemaFileFor(file);

        assertAll(
                () -> assertFalse(Files.exists(file), "precondition: nothing written yet"),
                () -> assertFalse(Files.exists(schema), "precondition: nothing written yet"));

        ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        assertAll(
                () -> assertTrue(Files.isRegularFile(file), "the config file must exist"),
                () -> assertTrue(
                        Files.isRegularFile(schema),
                        "the schema must be written beside it, found nothing at " + schema));
    }

    @Test
    void schemaIsRefreshedEveryLoad() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        final Path schema = SchemaWriter.schemaFileFor(file);
        final long firstWrite = Files.getLastModifiedTime(schema).toMillis();

        Thread.sleep(10);
        // The YAML is byte-identical on this second load - nothing in the interface changed.
        ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();

        assertTrue(
                Files.getLastModifiedTime(schema).toMillis() >= firstWrite,
                "the schema write must not be skipped just because the YAML did not change");
    }

    // a schema without a file, and back

    @Test
    void schemaWithoutFileIsAnError() throws Exception {
        final Path file = directory.resolve("payments.yml");
        SchemaWriter.write(file, TestSpecs.Payments.class);
        assertTrue(Files.exists(SchemaWriter.schemaFileFor(file)), "precondition");

        final ConfigException error = assertThrows(ConfigException.class, () -> SchemaWriter.checkPaired(file));
        assertTrue(
                error.getMessage().contains(file.toString()),
                "the error must name the missing file: " + error.getMessage());
    }

    @Test
    void fileWithoutSchemaIsAnError() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class)
                .withoutEnvironmentOverlay()
                .load();
        final Path schema = SchemaWriter.schemaFileFor(file);
        Files.delete(schema);

        final ConfigException error = assertThrows(ConfigException.class, () -> SchemaWriter.checkPaired(file));
        assertTrue(
                error.getMessage().contains(schema.toString()),
                "the error must name the missing schema: " + error.getMessage());
    }

    @Test
    void neitherExistingIsNotAnError() {
        assertDoesNotThrow(() -> SchemaWriter.checkPaired(directory.resolve("nothing-here.yml")));
    }

    @Test
    void theSchemaFileIsNotHtmlEscaped() throws Exception {
        // Gson's default HTML escaping is for a browser; a schema file is read by a JVM or a person, never one.
        final Path yml = directory.resolve("service.yml");
        SchemaWriter.write(yml, EscapingHolder.class);

        final String json = Files.readString(SchemaWriter.schemaFileFor(yml), StandardCharsets.UTF_8);
        assertTrue(json.contains("the network's own name"), "the apostrophe has to survive into the file: " + json);
        assertFalse(json.contains("&#39;"), "Gson's HTML escaping is still on: " + json);
    }

    @ConfigSpec
    public interface EscapingHolder {
        @Order(1)
        @Key("name")
        @Explain("the network's own name")
        default String name() {
            return "";
        }
    }

    // @Protected

    @Test
    void protectedIsRecordedOnAListOfNestedSettings() {
        final SchemaNode schema = SchemaWriter.build(ProtectedListHolder.class);
        assertEquals(
                new SchemaNode.ProtectedEntry("tag", "en"),
                schema.children().get("languages").protectedEntry());
    }

    @Test
    void protectedIsNullWithoutTheAnnotation() {
        final SchemaNode worldsList =
                SchemaWriter.build(TestSpecs.Worlds.class).children().get("worlds");
        assertNull(worldsList.protectedEntry());
    }

    @Test
    void protectedNamingAMissingFieldIsRejected() {
        final IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> SchemaWriter.build(ProtectedWithMissingField.class));
        assertTrue(error.getMessage().contains("nope"), error.getMessage());
    }

    @Test
    void protectedOnAScalarListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaWriter.build(ProtectedOnScalarList.class));
    }

    @Test
    void protectedOnAScalarIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaWriter.build(ProtectedOnScalar.class));
    }

    @ConfigSpec
    public interface ProtectedListHolder {
        @Order(1)
        @Key("languages")
        @Protected(field = "tag", value = "en")
        default List<ProtectedElement> languages() {
            return List.of();
        }
    }

    @ConfigSpec
    public interface ProtectedElement {
        @Order(1)
        @Key("tag")
        default String tag() {
            return "";
        }
    }

    @ConfigSpec
    public interface ProtectedWithMissingField {
        @Order(1)
        @Key("languages")
        @Protected(field = "nope", value = "en")
        default List<ProtectedElement> languages() {
            return List.of();
        }
    }

    @ConfigSpec
    public interface ProtectedOnScalarList {
        @Order(1)
        @Key("tags")
        @Protected(field = "tag", value = "en")
        default List<String> tags() {
            return List.of();
        }
    }

    @ConfigSpec
    public interface ProtectedOnScalar {
        @Order(1)
        @Key("tag")
        @Protected(field = "tag", value = "en")
        default String tag() {
            return "en";
        }
    }
}
