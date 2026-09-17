package eu.nordtal.jcore.config.schema;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.TestSpecs;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * steward/54: jcore writes a {@code config.schema.json} beside the YAML it writes, and the YAML
 * stops carrying comments. Each test here was watched fail before the corresponding piece of
 * {@link SchemaWriter} / {@code ConfigHandle} existed - see the ticket for the recorded output.
 */
class SchemaWriterTest {

    @TempDir
    Path directory;

    // ---------------------------------------------------------------- the annotation split

    @Test
    @DisplayName("@Explain's short text lands in the schema; @Comment's long text never reaches the YAML")
    void explainTextGoesToSchemaAndNeverToYaml() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final String yaml = Files.readString(file);
        assertAll(
                () -> assertFalse(yaml.contains("How often the account is polled"),
                        "the long @Comment text must never reach the YAML: " + yaml),
                () -> assertFalse(yaml.lines().anyMatch(line -> line.strip().startsWith("#")),
                        "the YAML must carry no comment lines at all: " + yaml)
        );

        final SchemaNode schema = SchemaWriter.build(TestSpecs.Payments.class);
        final SchemaNode checkInterval = schema.children().get("check-interval-seconds");
        assertAll(
                () -> assertEquals("How often payments are checked, in seconds.", checkInterval.explanation(),
                        "the schema must carry @Explain's short text"),
                () -> assertFalse(checkInterval.explanation().contains("How often the account is polled"),
                        "the schema must not carry @Comment's long text")
        );
    }

    @Test
    @DisplayName("a property with none of @Explain, @Comment or @NoExplanationNeeded gets an empty explanation, not an error")
    void unmigratedPropertyGetsAnEmptyExplanation() {
        // Colliding.ab() carries no annotation at all - not even @Comment - which is the one case
        // left where the schema still has nothing to say.
        final SchemaNode schema = SchemaWriter.build(TestSpecs.Colliding.class);
        final SchemaNode ab = schema.children().get("a-b");
        assertAll(
                () -> assertEquals("", ab.explanation()),
                () -> assertFalse(ab.noExplanationNeeded())
        );
    }

    @Test
    @DisplayName("a property with only @Comment gets that text as its schema explanation")
    void commentIsUsedAsAFallbackWhenNoExplainIsGiven() {
        // steward/72: 4.0.0 moved only @Explain's short text into the schema and left @Comment's
        // long text reaching no file at all - measured on the running SMP as 124 of 125 settings
        // showing an empty field where a paragraph used to be. Balance.channelId() carries
        // @Comment and no @Explain, which is the ordinary, unmigrated case, not an edge case.
        final SchemaNode schema = SchemaWriter.build(TestSpecs.Balance.class);
        final SchemaNode channelId = schema.children().get("channel-id");
        assertEquals("The voice channel that shows the balance.", channelId.explanation(),
                "an unmigrated property must fall back to its @Comment text, not stay empty");
    }

    @Test
    @DisplayName("a multi-line @Comment is joined with newlines into one explanation string")
    void multiLineCommentIsJoinedWithNewlines() {
        final SchemaNode schema = SchemaWriter.build(MultiLineCommentOnly.class);
        assertEquals("First line.\n\nSecond paragraph line.",
                schema.children().get("option").explanation(),
                "@Comment is a String[], one array entry per line - the browser already renders "
                        + "a multi-line explanation, so a newline join keeps a blank-line paragraph "
                        + "break intact instead of running everything onto one line");
    }

    // The property name is deliberately not "setting" - the vendored Spec reads any method
    // beginning with "set" as a setter (see the comment on Contradiction below).
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
    @DisplayName("@NoExplanationNeeded is recorded, with an empty explanation text")
    void noExplanationNeededIsRecorded() {
        final SchemaNode schema = SchemaWriter.build(TestSpecs.SchemaExample.class);
        final SchemaNode internalId = schema.children().get("internal-id");
        assertAll(
                () -> assertTrue(internalId.noExplanationNeeded()),
                () -> assertEquals("", internalId.explanation())
        );
    }

    @Test
    @DisplayName("@Explain and @NoExplanationNeeded together is refused rather than guessed at")
    void explainAndNoExplanationNeededTogetherIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaWriter.build(Contradiction.class));
    }

    @ConfigSpec
    public interface Contradiction {
        // NOT called `setting()`, and that is the whole point (found 2026-09-16 while adding the
        // header tests below). The vendored Spec reads any method whose name begins with `set` as
        // a setter, so `setting()` is rejected with "Setter for property 'ting' must return void!"
        // before @Explain and @NoExplanationNeeded are ever looked at - which means this test threw
        // the right exception type for the wrong reason, and had done since steward/54. It would
        // have kept passing if the contradiction check were deleted outright.
        @Explain("short")
        @NoExplanationNeeded
        default String option() {
            return "";
        }
    }

    // ---------------------------------------------------------------- the file-level header

    @Test
    @DisplayName("@ConfigSpec(header) lands on the root node's explanation, one entry per line")
    void headerBecomesTheRootExplanation() {
        // steward/67: 4.0.0 stopped writing the header into the YAML and put nothing in its place,
        // so a spec's header was written to no file at all. season-2's BotSpec uses its header for
        // the only sentence that tells an operator the token comes from NORDTAL_BOT_TOKEN rather
        // than from the file - text nobody could afford to lose to a refactor.
        final SchemaNode schema = SchemaWriter.build(TestSpecs.Payments.class);
        assertEquals("Test configuration\nSecond header line", schema.explanation());
    }

    @Test
    @DisplayName("a one-line header is that line, with no trailing newline bolted on")
    void singleLineHeaderIsJustThatLine() {
        assertEquals("Worlds", SchemaWriter.build(TestSpecs.Worlds.class).explanation());
    }

    @Test
    @DisplayName("blank lines inside a header survive - a paragraph break is part of the prose")
    void blankLinesInsideAHeaderSurvive() {
        // BotSpec's header is a block, a blank line, an indented list, a blank line and a closing
        // sentence. Dropping the empty entries would run all of it into one paragraph.
        assertEquals("First paragraph.\n\nSecond paragraph.",
                SchemaWriter.build(HeaderWithBlankLine.class).explanation());
    }

    // The property name here is deliberately not "setting": the vendored Spec reads any method
    // beginning with "set" as a setter, so `String setting()` is rejected as "setter for property
    // 'ting' must return void" before the header is ever looked at.
    @ConfigSpec(header = {"First paragraph.", "", "Second paragraph."})
    public interface HeaderWithBlankLine {
        default String option() {
            return "";
        }
    }

    @Test
    @DisplayName("a header entry that itself contains a newline is split, not doubled")
    void embeddedNewlineIsOneLineBreakAndNotTwo() {
        // headerOf() splits every entry on '\n' before this ever sees it, so joining with '\n'
        // has to give the text back unchanged rather than turning one break into two.
        assertEquals("One\nTwo\nThree", SchemaWriter.build(HeaderWithEmbeddedNewline.class).explanation());
    }

    @ConfigSpec(header = {"One\nTwo", "Three"})
    public interface HeaderWithEmbeddedNewline {
        default String option() {
            return "";
        }
    }

    @Test
    @DisplayName("no header at all stays the empty string - never a placeholder")
    void absentHeaderStaysEmpty() {
        assertEquals("", SchemaWriter.build(TestSpecs.Balance.class).explanation());
    }

    @Test
    @DisplayName("the root's label stays empty - a header is prose, and a label is a name")
    void theRootKeepsNoLabel() {
        assertEquals("", SchemaWriter.build(TestSpecs.Payments.class).label());
    }

    @Test
    @DisplayName("the header reaches the written schema file, not only the in-memory tree")
    void headerIsInTheWrittenSchemaFile() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        final String json = Files.readString(SchemaWriter.schemaFileFor(file));
        assertTrue(json.contains("Test configuration\\nSecond header line"),
                "the schema file must carry the header text: " + json);

        // And still not the YAML - 4.0.0's decision is not being walked back here.
        assertFalse(Files.readString(file).contains("Test configuration"),
                "the header must not return to the YAML");
    }

    @Test
    @DisplayName("a nested spec's own header does not leak onto the parent's child node")
    void aNestedSpecsHeaderIsNotTheChildsExplanation() {
        // A child node's explanation belongs to the property that declares it (@Explain on the
        // getter), not to the interface behind it. Balance has no header, so make one that does.
        final SchemaNode outer = SchemaWriter.build(Outer.class);
        assertEquals("", outer.children().get("inner").explanation(),
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

    // ---------------------------------------------------------------- secret

    @Test
    @DisplayName("@Secret is recorded on the schema entry")
    void secretIsRecorded() {
        final SchemaNode schema = SchemaWriter.build(TestSpecs.SchemaExample.class);
        assertAll(
                () -> assertTrue(schema.children().get("api-token").secret()),
                () -> assertFalse(schema.children().get("region").secret())
        );
    }

    // ---------------------------------------------------------------- allowed values

    @Test
    @DisplayName("@AllowedValues(strict) is a closed list")
    void strictAllowedValues() {
        final SchemaNode region = SchemaWriter.build(TestSpecs.SchemaExample.class).children().get("region");
        assertAll(
                () -> assertEquals(List.of("eu", "us"), region.choices().values()),
                () -> assertTrue(region.choices().strict())
        );
    }

    @Test
    @DisplayName("@AllowedValues(strict = false) is a suggestion beside free text")
    void suggestionAllowedValues() {
        final SchemaNode colour = SchemaWriter.build(TestSpecs.SchemaExample.class).children().get("accent-colour");
        assertAll(
                () -> assertEquals(List.of("red", "green", "blue"), colour.choices().values()),
                () -> assertFalse(colour.choices().strict())
        );
    }

    @Test
    @DisplayName("a Java enum property gets its allowed values for free, and is always strict")
    void enumPropertyGetsAllowedValuesAutomatically() {
        final SchemaNode mode = SchemaWriter.build(TestSpecs.SchemaExample.class).children().get("mode");
        assertAll(
                () -> assertEquals(List.of("STRICT", "LOOSE"), mode.choices().values()),
                () -> assertTrue(mode.choices().strict()),
                () -> assertEquals(SettingType.STRING, mode.type())
        );
    }

    @Test
    @DisplayName("a plain scalar with no @AllowedValues has no choices at all - not an empty list")
    void plainScalarHasNoChoices() {
        final SchemaNode checkInterval = SchemaWriter.build(TestSpecs.Payments.class)
                .children().get("check-interval-seconds");
        assertNull(checkInterval.choices());
    }

    // ---------------------------------------------------------------- kind, type, and the group

    @Test
    @DisplayName("kind and type mirror what ConfigEntry already knows: a nested spec is a MAP, a list of scalars is a LIST")
    void kindAndTypeMirrorConfigEntry() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        assertAll(
                () -> assertEquals(SettingKind.SCALAR, payments.children().get("check-interval-seconds").kind()),
                () -> assertEquals(SettingType.INTEGER, payments.children().get("check-interval-seconds").type()),
                () -> assertEquals(SettingKind.MAP, payments.children().get("balance").kind()),
                () -> assertNull(payments.children().get("balance").type())
        );

        final SchemaNode worlds = SchemaWriter.build(TestSpecs.Worlds.class);
        final SchemaNode worldsList = worlds.children().get("worlds");
        assertAll(
                () -> assertEquals(SettingKind.LIST, worldsList.kind()),
                () -> assertTrue(worldsList.children().containsKey("preserved"),
                        "a list of nested specs describes the shape of one element"),
                () -> assertEquals(SettingType.BOOLEAN, worldsList.children().get("preserved").type())
        );
    }

    @Test
    @DisplayName("the group is the schema's own nesting - Balance's settings sit under 'balance', nowhere else")
    void groupIsTheNestingItself() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        final Map<String, SchemaNode> balanceChildren = payments.children().get("balance").children();
        assertAll(
                () -> assertTrue(balanceChildren.containsKey("channel-id")),
                () -> assertTrue(balanceChildren.containsKey("format")),
                () -> assertFalse(payments.children().containsKey("channel-id"),
                        "a nested setting must not also appear flattened at the root")
        );
    }

    @Test
    @DisplayName("children keep @Order's order - a schema is read by a person, not a HashMap")
    void childrenPreserveDeclarationOrder() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        assertEquals(
                List.of("check-interval-seconds", "confirmation-channel-id", "balance"),
                List.copyOf(payments.children().keySet()));
    }

    @Test
    @DisplayName("@Reload / @Save are proxy-handled and are not settings of their own")
    void proxyHandledMethodsAreNotSettings() {
        final SchemaNode payments = SchemaWriter.build(TestSpecs.Payments.class);
        assertFalse(payments.children().containsKey("reload"));
        assertFalse(payments.children().containsKey("save"));
    }

    // ---------------------------------------------------------------- file and schema come into being together

    @Test
    @DisplayName("a fresh load writes the file and its schema together")
    void fileAndSchemaComeIntoBeingTogether() throws Exception {
        final Path file = directory.resolve("payments.yml");
        final Path schema = SchemaWriter.schemaFileFor(file);

        assertAll(
                () -> assertFalse(Files.exists(file), "precondition: nothing written yet"),
                () -> assertFalse(Files.exists(schema), "precondition: nothing written yet")
        );

        ConfigLoader.builder(file, TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        assertAll(
                () -> assertTrue(Files.isRegularFile(file), "the config file must exist"),
                () -> assertTrue(Files.isRegularFile(schema),
                        "the schema must be written beside it, found nothing at " + schema)
        );
    }

    @Test
    @DisplayName("the schema is refreshed on every load, even when the YAML itself does not change")
    void schemaIsRefreshedEveryLoad() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        final Path schema = SchemaWriter.schemaFileFor(file);
        final long firstWrite = Files.getLastModifiedTime(schema).toMillis();

        Thread.sleep(10);
        // The YAML is byte-identical on this second load - nothing in the interface changed.
        ConfigLoader.builder(file, TestSpecs.Payments.class).withoutEnvironmentOverlay().load();

        assertTrue(Files.getLastModifiedTime(schema).toMillis() >= firstWrite,
                "the schema write must not be skipped just because the YAML did not change");
    }

    // ---------------------------------------------------------------- a schema without a file, and back

    @Test
    @DisplayName("a schema with no file behind it is an error")
    void schemaWithoutFileIsAnError() throws Exception {
        final Path file = directory.resolve("payments.yml");
        SchemaWriter.write(file, TestSpecs.Payments.class);
        assertTrue(Files.exists(SchemaWriter.schemaFileFor(file)), "precondition");

        final ConfigException error = assertThrows(ConfigException.class, () -> SchemaWriter.checkPaired(file));
        assertTrue(error.getMessage().contains(file.toString()),
                "the error must name the missing file: " + error.getMessage());
    }

    @Test
    @DisplayName("a file with no schema behind it is an error")
    void fileWithoutSchemaIsAnError() throws Exception {
        final Path file = directory.resolve("payments.yml");
        ConfigLoader.builder(file, TestSpecs.Payments.class).withoutEnvironmentOverlay().load();
        final Path schema = SchemaWriter.schemaFileFor(file);
        Files.delete(schema);

        final ConfigException error = assertThrows(ConfigException.class, () -> SchemaWriter.checkPaired(file));
        assertTrue(error.getMessage().contains(schema.toString()),
                "the error must name the missing schema: " + error.getMessage());
    }

    @Test
    @DisplayName("neither file nor schema existing is not an error - that is the fresh, not-yet-written state")
    void neitherExistingIsNotAnError() {
        assertDoesNotThrow(() -> SchemaWriter.checkPaired(directory.resolve("nothing-here.yml")));
    }

    @Test
    @DisplayName("the schema file keeps an apostrophe as an apostrophe, not as &#39;")
    void theSchemaFileIsNotHtmlEscaped() throws Exception {
        // steward/67: Gson escapes ' < > & by default, for JSON that is about to be pasted into
        // HTML. A schema file is read by a JVM and, occasionally, by a person opening it - never by
        // a browser. bot.schema.json carried &#39; in its file header for exactly that reason.
        final Path yml = directory.resolve("service.yml");
        SchemaWriter.write(yml, EscapingHolder.class);

        final String json = Files.readString(SchemaWriter.schemaFileFor(yml), StandardCharsets.UTF_8);
        assertTrue(json.contains("the network's own name"),
                "the apostrophe has to survive into the file: " + json);
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

    // ---------------------------------------------------------------- @Protected (steward/74)

    @Test
    @DisplayName("@Protected on a list of nested settings is recorded as the schema's protectedEntry")
    void protectedIsRecordedOnAListOfNestedSettings() {
        final SchemaNode schema = SchemaWriter.build(ProtectedListHolder.class);
        assertEquals(new SchemaNode.ProtectedEntry("tag", "en"),
                schema.children().get("languages").protectedEntry());
    }

    @Test
    @DisplayName("a list of nested settings with no @Protected has a null protectedEntry, not a guessed one")
    void protectedIsNullWithoutTheAnnotation() {
        final SchemaNode worldsList = SchemaWriter.build(TestSpecs.Worlds.class).children().get("worlds");
        assertNull(worldsList.protectedEntry());
    }

    @Test
    @DisplayName("@Protected naming a field the element type does not have is refused, not silently useless")
    void protectedNamingAMissingFieldIsRejected() {
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SchemaWriter.build(ProtectedWithMissingField.class));
        assertTrue(error.getMessage().contains("nope"), error.getMessage());
    }

    @Test
    @DisplayName("@Protected on a list of plain scalars is refused - there is no field to match against")
    void protectedOnAScalarListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SchemaWriter.build(ProtectedOnScalarList.class));
    }

    @Test
    @DisplayName("@Protected on a plain scalar property is refused, the same way")
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
