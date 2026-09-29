package eu.nordtal.s2.steward.worker.configfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A YAML sequence of mappings, read as {@link Kind#SECTIONS} and written back field by field.
 *
 * {@link #TIERS_FIXTURE} is {@code tiers:} from the bot's real {@code access.yml}, byte for byte.
 */
class ConfigFilesSectionsTest {

    @TempDir
    Path directory;

    private static final String TIERS_FIXTURE = """
            tiers:

              # How many days of access this buys. A day is exactly 24 hours.
            - days: 30

              # What it costs, in cents. Integer cents everywhere; never a float.
              price-cents: 300
            - days: 60
              price-cents: 500
            - days: 90
              price-cents: 700
            """;

    // Reading

    @Test
    void aSequenceOfMappingsReadsAsSections() throws IOException {
        final ConfigEntry tiers = entry(read(TIERS_FIXTURE), "tiers");

        assertEquals(Kind.SECTIONS, tiers.kind());
        assertTrue(tiers.editable());
        assertEquals(3, tiers.sections().size());
        assertEquals("30", fieldValue(tiers, 0, "days"));
        assertEquals("300", fieldValue(tiers, 0, "price-cents"));
        assertEquals("60", fieldValue(tiers, 1, "days"));
        assertEquals("500", fieldValue(tiers, 1, "price-cents"));
        assertEquals("90", fieldValue(tiers, 2, "days"));
        assertEquals("700", fieldValue(tiers, 2, "price-cents"));
    }

    @Test
    void aFieldKeepsTheCommentDirectlyAboveItEvenBetweenTwoOtherFields() throws IOException {
        final ConfigEntry tiers = entry(read(TIERS_FIXTURE), "tiers");

        assertEquals(
                List.of("How many days of access this buys. A day is exactly 24 hours."),
                fieldOf(tiers, 0, "days").comments());
        assertEquals(
                List.of("What it costs, in cents. Integer cents everywhere; never a float."),
                fieldOf(tiers, 0, "price-cents").comments());
        // jcore never wrote a comment for the second and third entry.
        assertEquals(List.of(), fieldOf(tiers, 1, "days").comments());
        assertEquals(List.of(), fieldOf(tiers, 1, "price-cents").comments());
        assertEquals(List.of(), fieldOf(tiers, 2, "days").comments());
        assertEquals(List.of(), fieldOf(tiers, 2, "price-cents").comments());
    }

    @Test
    void withNoSchemaThereIsNoTemplateAndTheFieldsAreStillDelivered() throws IOException {
        final ConfigEntry tiers = entry(read(TIERS_FIXTURE), "tiers");

        // No schema for this fixture, so no blank card shape, but the fields themselves are there.
        assertEquals(List.of(), tiers.template());
        assertEquals(3, tiers.sections().size());
    }

    @Test
    void theFieldsOfASectionAreNotAlsoFlattenedIntoTheDocumentsOwnList() throws IOException {
        final ConfigDocument document = read(TIERS_FIXTURE);

        assertTrue(
                document.find("tiers[0].days").isEmpty(),
                "a section's own fields are reached through ConfigEntry.sections(), not find()");
        assertEquals(
                List.of("tiers"),
                document.entries().stream().map(ConfigEntry::path).toList());
    }

    @Test
    void aSequenceThatMixesScalarsAndMappingsStaysAPlainUneditableList() throws IOException {
        final ConfigEntry mixed = entry(read("mixed:\n- one\n- key: value\n"), "mixed");

        assertEquals(Kind.LIST, mixed.kind());
        assertFalse(mixed.editable());
        assertEquals(List.of(), mixed.sections());
        // The scalar collector still finds the one scalar entry; not editable since the sequence is not one shape.
        assertEquals(List.of("one"), mixed.items());
    }

    @Test
    void anEmptySequenceStaysAPlainEditableList() throws IOException {
        final ConfigEntry tiers = entry(read("tiers: []\n"), "tiers");

        assertEquals(Kind.LIST, tiers.kind());
        assertTrue(tiers.editable());
    }

    // Writing

    @Test
    void changingOneFieldLeavesEveryOtherEntryByteIdentical() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "350"), // the only change
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700")))));

        assertEquals(
                before.replace("price-cents: 300", "price-cents: 350"),
                Files.readString(file),
                "every byte other than the one changed value must be exactly as it was - the"
                        + " comments and the key order of the untouched entries included");
    }

    @Test
    void theWrittenSectionsReadBackTheNewValueAndNothingElseChanged() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final ConfigDocument written = ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "350"),
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700")))));

        final ConfigEntry tiers = entry(written, "tiers");
        assertEquals("30", fieldValue(tiers, 0, "days"));
        assertEquals("350", fieldValue(tiers, 0, "price-cents"));
        assertEquals("60", fieldValue(tiers, 1, "days"));
        assertEquals("500", fieldValue(tiers, 1, "price-cents"));
    }

    @Test
    void changingFieldsInTwoDifferentEntriesTouchesOnlyThoseTwoLines() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "31", "price-cents", "300"),
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "750")))));

        assertEquals(
                before.replace("days: 30", "days: 31").replace("price-cents: 700", "price-cents: 750"),
                Files.readString(file));
    }

    @Test
    void sendingBackTheSameValuesChangesNothingAtAll() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "300"),
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700")))));

        assertEquals(before, Files.readString(file));
    }

    // Writing: secrets inside a card

    /** Two webhooks, each with a token the browser never sees and so sends back empty. */
    private static final String HOOKS_FIXTURE = """
            hooks:
            - name: admin
              token: first-secret
            - name: log
              token: second-secret
            """;

    @Test
    void aSecretFieldInACardIsTakenAsASecret() throws IOException {
        assertTrue(fieldOf(entry(read(HOOKS_FIXTURE), "hooks"), 0, "token").secret());
    }

    @Test
    void anEmptySecretInACardLeavesTheStoredOneAlone() throws IOException {
        final Path file = directory.resolve("hooks.yml");
        Files.writeString(file, HOOKS_FIXTURE);

        ConfigFiles.write(
                file,
                Map.of(
                        "hooks",
                        ConfigChange.sections(
                                List.of(Map.of("name", "admins", "token", ""), Map.of("name", "log", "token", "")))));

        assertEquals(HOOKS_FIXTURE.replace("name: admin\n", "name: admins\n"), Files.readString(file));
    }

    @Test
    void aSecretInACardCanStillBeReplaced() throws IOException {
        final Path file = directory.resolve("hooks.yml");
        Files.writeString(file, HOOKS_FIXTURE);

        ConfigFiles.write(
                file,
                Map.of(
                        "hooks",
                        ConfigChange.sections(List.of(
                                Map.of("name", "admin", "token", ""),
                                Map.of("name", "log", "token", "third-secret")))));

        assertEquals(HOOKS_FIXTURE.replace("second-secret", "third-secret"), Files.readString(file));
    }

    @Test
    void cardsWithEmptySecretsCanBeAddedToAndRemovedFrom() throws IOException {
        final Path file = directory.resolve("hooks.yml");
        Files.writeString(file, HOOKS_FIXTURE);

        ConfigFiles.write(
                file,
                Map.of(
                        "hooks",
                        ConfigChange.sections(List.of(
                                Map.of("name", "admin", "token", ""),
                                Map.of("name", "log", "token", ""),
                                Map.of("name", "alerts", "token", "new-secret")))));
        assertTrue(Files.readString(file).endsWith("- name: alerts\n  token: new-secret\n"));

        ConfigFiles.write(
                file,
                Map.of(
                        "hooks",
                        ConfigChange.sections(
                                List.of(Map.of("name", "admin", "token", ""), Map.of("name", "alerts", "token", "")))));
        final String left = Files.readString(file);
        assertTrue(left.contains("first-secret") && left.contains("new-secret"), left);
        assertFalse(left.contains("second-secret"), left);
    }

    // Writing: appending and removing

    /**
     * Appending a fourth tier leaves the three existing entries byte-identical.
     *
     * The new entry copies the shape of the entry it follows: no comment, same indentation, no blank line.
     */
    @Test
    void appendingAnEntryLeavesEveryExistingEntryByteIdenticalAndCopiesTheLastEntrysStyle() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        final ConfigDocument written = ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "300"),
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700"),
                                Map.of("days", "120", "price-cents", "900")))));

        assertEquals(
                before + "- days: 120\n  price-cents: 900\n",
                Files.readString(file),
                "the three existing entries must be untouched, and the new one written in the same"
                        + " shape (no comment, no blank line) as the last existing entry");

        final ConfigEntry tiers = entry(written, "tiers");
        assertEquals(4, tiers.sections().size());
        assertEquals("120", fieldValue(tiers, 3, "days"));
        assertEquals("900", fieldValue(tiers, 3, "price-cents"));
    }

    @Test
    void appendingCopiesABlankLineBeforeEachEntryWhenTheLastEntryHadOne() throws IOException {
        final String fixture = """
                tiers:
                - days: 30
                  price-cents: 300

                - days: 60
                  price-cents: 500
                """;
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, fixture);

        ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "300"),
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700")))));

        assertEquals(
                fixture + "\n- days: 90\n  price-cents: 700\n",
                Files.readString(file),
                "the last entry had a blank line before it, so the new one gets one too");
    }

    @Test
    void appendingCopiesTheIndentationOfTheLastEntry() throws IOException {
        final String fixture = """
                tiers:
                  - days: 30
                    price-cents: 300
                  - days: 60
                    price-cents: 500
                """;
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, fixture);

        ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "300"),
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700")))));

        assertEquals(fixture + "  - days: 90\n    price-cents: 700\n", Files.readString(file));
    }

    /**
     * The bot's real {@code access.yml} is a list of STRING fields, most of them empty.
     *
     * An appended empty string must read back as empty, not as the file text {@code ''}.
     */
    @Test
    void appendingAnEmptyStringFieldReadsBackEmptyNotQuoted() throws IOException {
        final String fixture = """
                languages:
                - tag: en
                  role: ''
                - tag: de
                  role: ''
                """;
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, fixture);

        final ConfigDocument written = ConfigFiles.write(
                file,
                Map.of(
                        "languages",
                        ConfigChange.sections(List.of(
                                Map.of("tag", "en", "role", ""),
                                Map.of("tag", "de", "role", ""),
                                Map.of("tag", "fr", "role", "")))));

        assertEquals(fixture + "- tag: fr\n  role: ''\n", Files.readString(file));
        final ConfigEntry languages = entry(written, "languages");
        assertEquals(
                "",
                fieldValue(languages, 2, "role"),
                "the logical value of an empty string field is the empty string, not \"''\"");
    }

    @Test
    void appendingWhileAlsoEditingAnExistingEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(
                        file,
                        Map.of(
                                "tiers",
                                ConfigChange.sections(List.of(
                                        Map.of("days", "31", "price-cents", "300"), // changed alongside the append
                                        Map.of("days", "60", "price-cents", "500"),
                                        Map.of("days", "90", "price-cents", "700"),
                                        Map.of("days", "120", "price-cents", "900"))))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertEquals(before, Files.readString(file), "a refused write must not touch the file");
    }

    @Test
    void addingTwoEntriesAtOnceIsRefusedAndTheFileIsUntouched() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(
                        file,
                        Map.of(
                                "tiers",
                                ConfigChange.sections(List.of(
                                        Map.of("days", "30", "price-cents", "300"),
                                        Map.of("days", "60", "price-cents", "500"),
                                        Map.of("days", "90", "price-cents", "700"),
                                        Map.of("days", "120", "price-cents", "900"),
                                        Map.of("days", "150", "price-cents", "1100"))))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertEquals(before, Files.readString(file), "a refused write must not touch the file");
    }

    /** A comment directly above an entry's own line goes with that entry when it is removed. */
    private static final String TIERS_WITH_A_COMMENT_BETWEEN_ENTRIES = """
            tiers:
            - days: 30
              price-cents: 300
            # about the 60-day tier
            - days: 60
              price-cents: 500
            - days: 90
              price-cents: 700
            """;

    @Test
    void removingAnEntryDropsOnlyTheCommentThatBelongsToIt() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_WITH_A_COMMENT_BETWEEN_ENTRIES);

        // Removing the FIRST entry (days: 30) must leave the 60-day comment alone: it sits above that entry's own line.
        final ConfigDocument written = ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "60", "price-cents", "500"),
                                Map.of("days", "90", "price-cents", "700")))));

        assertEquals("""
                tiers:
                # about the 60-day tier
                - days: 60
                  price-cents: 500
                - days: 90
                  price-cents: 700
                """, Files.readString(file));

        final ConfigEntry tiers = entry(written, "tiers");
        assertEquals(2, tiers.sections().size());
        assertEquals(List.of("about the 60-day tier"), fieldOf(tiers, 0, "days").comments());
    }

    @Test
    void removingAnEntryTakesTheCommentThatBelongsToItAndNoneOfTheNexts() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_WITH_A_COMMENT_BETWEEN_ENTRIES);

        // Removing the SECOND entry (days: 60) must take its own comment with it, leaving the 90-day entry untouched.
        ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "300"),
                                Map.of("days", "90", "price-cents", "700")))));

        assertEquals("""
                tiers:
                - days: 30
                  price-cents: 300
                - days: 90
                  price-cents: 700
                """, Files.readString(file));
    }

    @Test
    void removingTheLastEntryReadsBackOneFewerSection() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final ConfigDocument written = ConfigFiles.write(
                file,
                Map.of(
                        "tiers",
                        ConfigChange.sections(List.of(
                                Map.of("days", "30", "price-cents", "300"),
                                Map.of("days", "60", "price-cents", "500")))));

        assertEquals(TIERS_FIXTURE.replace("- days: 90\n  price-cents: 700\n", ""), Files.readString(file));
        assertEquals(2, entry(written, "tiers").sections().size());
    }

    @Test
    void removingWhileAlsoEditingAnotherEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(
                        file,
                        Map.of(
                                "tiers",
                                ConfigChange.sections(List.of(
                                        Map.of("days", "31", "price-cents", "300"), // changed, not just removed
                                        Map.of("days", "60", "price-cents", "500"))))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertEquals(before, Files.readString(file), "a refused write must not touch the file");
    }

    @Test
    void removingTwoEntriesAtOnceIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(
                        file,
                        Map.of("tiers", ConfigChange.sections(List.of(Map.of("days", "30", "price-cents", "300"))))));
    }

    // Writing: refusals

    @Test
    void aFieldMissingFromASentEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(
                        file,
                        Map.of(
                                "tiers",
                                ConfigChange.sections(List.of(
                                        Map.of("days", "30"),
                                        Map.of("days", "60", "price-cents", "500"),
                                        Map.of("days", "90", "price-cents", "700"))))));

        assertTrue(thrown.getMessage().contains("price-cents"), thrown.getMessage());
    }

    @Test
    void sendingAPlainListOfValuesToASectionsEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.list(List.of("30", "60")))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("list of sections"), thrown.getMessage());
    }

    @Test
    void sendingASingleValueToASectionsEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.of("30"))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("list of sections"), thrown.getMessage());
    }

    @Test
    void sendingSectionRecordsToAPlainScalarIsRefused() throws IOException {
        final Path file = directory.resolve("service.yml");
        Files.writeString(file, "port: 8080\n");

        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("port", ConfigChange.sections(List.of(Map.of("x", "y"))))));

        assertTrue(thrown.getMessage().contains("port"), thrown.getMessage());
    }

    // Fixtures

    private ConfigDocument read(final String yaml) throws IOException {
        final Path file = directory.resolve("fixture-" + System.nanoTime() + ".yml");
        Files.writeString(file, yaml);
        return ConfigFiles.read(file);
    }

    private static ConfigEntry entry(final ConfigDocument document, final String path) {
        return document.find(path)
                .orElseThrow(() -> new AssertionError("no entry " + path + " in "
                        + document.entries().stream().map(ConfigEntry::path).toList()));
    }

    private static ConfigEntry fieldOf(final ConfigEntry sectionsEntry, final int index, final String key) {
        return sectionsEntry.sections().get(index).stream()
                .filter(field -> field.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + key + " in entry " + index));
    }

    private static String fieldValue(final ConfigEntry sectionsEntry, final int index, final String key) {
        return fieldOf(sectionsEntry, index, key).value();
    }
}
