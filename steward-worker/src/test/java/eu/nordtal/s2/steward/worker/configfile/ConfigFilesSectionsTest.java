package eu.nordtal.s2.steward.worker.configfile;

import eu.nordtal.s2.steward.worker.configfile.ConfigEntry.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A YAML sequence of mappings, read as {@link Kind#SECTIONS} and - for an existing entry's own
 * field - written back (steward/68).
 *
 * <p>{@link #TIERS_FIXTURE} is {@code tiers:} out of the bot's real {@code access.yml}, copied
 * byte for byte on 2026-09-16 (comments included) rather than typed to be easy to parse - it is
 * exactly the shape the old comment on {@link ConfigFiles}'s single {@code Kind.LIST} branch
 * warned a rewrite could not survive: a comment sitting <em>between</em> the two fields of the
 * first entry, and no comment at all on the second and third. {@link
 * #changingOneFieldLeavesEveryOtherEntryByteIdentical()} is the proof the ticket asks for -
 * without it, {@link ConfigChange.Sections} would not exist and this whole class would not
 * compile, so "red" here started as a compiler error against the pre-steward/68 shape rather
 * than a failing assertion.</p>
 */
class ConfigFilesSectionsTest {

    @TempDir
    Path directory;

    private static final String TIERS_FIXTURE =
            "tiers:\n"
            + "\n"
            + "  # How many days of access this buys. A day is exactly 24 hours.\n"
            + "- days: 30\n"
            + "\n"
            + "  # What it costs, in cents. Integer cents everywhere; never a float.\n"
            + "  price-cents: 300\n"
            + "- days: 60\n"
            + "  price-cents: 500\n"
            + "- days: 90\n"
            + "  price-cents: 700\n";

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

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

        assertEquals(List.of("How many days of access this buys. A day is exactly 24 hours."),
                fieldOf(tiers, 0, "days").comments());
        assertEquals(List.of("What it costs, in cents. Integer cents everywhere; never a float."),
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

        // No schema for this fixture, so there is nothing to build a blank card's shape from - but
        // steward/50's "the file is the truth" still applies: the fields themselves are there.
        assertEquals(List.of(), tiers.template());
        assertEquals(3, tiers.sections().size());
    }

    @Test
    void theFieldsOfASectionAreNotAlsoFlattenedIntoTheDocumentsOwnList() throws IOException {
        final ConfigDocument document = read(TIERS_FIXTURE);

        assertTrue(document.find("tiers[0].days").isEmpty(),
                "a section's own fields are reached through ConfigEntry.sections(), not find()");
        assertEquals(List.of("tiers"), document.entries().stream().map(ConfigEntry::path).toList());
    }

    @Test
    void aSequenceThatMixesScalarsAndMappingsStaysAPlainUneditableList() throws IOException {
        final ConfigEntry mixed = entry(read("mixed:\n- one\n- key: value\n"), "mixed");

        assertEquals(Kind.LIST, mixed.kind());
        assertFalse(mixed.editable());
        assertEquals(List.of(), mixed.sections());
        // Unchanged from before steward/68: the scalar collector still finds the one scalar entry,
        // it is simply not editable because the sequence as a whole is not all one shape.
        assertEquals(List.of("one"), mixed.items());
    }

    @Test
    void anEmptySequenceStaysAPlainEditableList() throws IOException {
        final ConfigEntry tiers = entry(read("tiers: []\n"), "tiers");

        assertEquals(Kind.LIST, tiers.kind());
        assertTrue(tiers.editable());
    }

    // -----------------------------------------------------------------------------------------
    // Writing - the proof steward/68 asks for
    // -----------------------------------------------------------------------------------------

    @Test
    void changingOneFieldLeavesEveryOtherEntryByteIdentical() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
                Map.of("days", "30", "price-cents", "350"), // the only change
                Map.of("days", "60", "price-cents", "500"),
                Map.of("days", "90", "price-cents", "700")))));

        assertEquals(before.replace("price-cents: 300", "price-cents: 350"), Files.readString(file),
                "every byte other than the one changed value must be exactly as it was - the"
                        + " comments and the key order of the untouched entries included");
    }

    @Test
    void theWrittenSectionsReadBackTheNewValueAndNothingElseChanged() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final ConfigDocument written = ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
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

        ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
                Map.of("days", "31", "price-cents", "300"),
                Map.of("days", "60", "price-cents", "500"),
                Map.of("days", "90", "price-cents", "750")))));

        assertEquals(before.replace("days: 30", "days: 31")
                        .replace("price-cents: 700", "price-cents: 750"),
                Files.readString(file));
    }

    @Test
    void sendingBackTheSameValuesChangesNothingAtAll() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
                Map.of("days", "30", "price-cents", "300"),
                Map.of("days", "60", "price-cents", "500"),
                Map.of("days", "90", "price-cents", "700")))));

        assertEquals(before, Files.readString(file));
    }

    // -----------------------------------------------------------------------------------------
    // Writing - refusals
    // -----------------------------------------------------------------------------------------

    @Test
    void addingAnEntryIsRefusedAndTheFileIsUntouched() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);
        final String before = Files.readString(file);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
                        Map.of("days", "30", "price-cents", "300"),
                        Map.of("days", "60", "price-cents", "500"),
                        Map.of("days", "90", "price-cents", "700"),
                        Map.of("days", "120", "price-cents", "900"))))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertEquals(before, Files.readString(file), "a refused write must not touch the file");
    }

    @Test
    void removingAnEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
                        Map.of("days", "30", "price-cents", "300"),
                        Map.of("days", "60", "price-cents", "500"))))));
    }

    @Test
    void aFieldMissingFromASentEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.sections(List.of(
                        Map.of("days", "30"),
                        Map.of("days", "60", "price-cents", "500"),
                        Map.of("days", "90", "price-cents", "700"))))));

        assertTrue(thrown.getMessage().contains("price-cents"), thrown.getMessage());
    }

    @Test
    void sendingAPlainListOfValuesToASectionsEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.list(List.of("30", "60")))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("list of sections"), thrown.getMessage());
    }

    @Test
    void sendingASingleValueToASectionsEntryIsRefused() throws IOException {
        final Path file = directory.resolve("access.yml");
        Files.writeString(file, TIERS_FIXTURE);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("tiers", ConfigChange.of("30"))));

        assertTrue(thrown.getMessage().contains("tiers"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("list of sections"), thrown.getMessage());
    }

    @Test
    void sendingSectionRecordsToAPlainScalarIsRefused() throws IOException {
        final Path file = directory.resolve("service.yml");
        Files.writeString(file, "port: 8080\n");

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ConfigFiles.write(file, Map.of("port",
                        ConfigChange.sections(List.of(Map.of("x", "y"))))));

        assertTrue(thrown.getMessage().contains("port"), thrown.getMessage());
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private ConfigDocument read(final String yaml) throws IOException {
        final Path file = directory.resolve("fixture-" + System.nanoTime() + ".yml");
        Files.writeString(file, yaml);
        return ConfigFiles.read(file);
    }

    private static ConfigEntry entry(final ConfigDocument document, final String path) {
        return document.find(path).orElseThrow(
                () -> new AssertionError("no entry " + path + " in " + document.entries().stream()
                        .map(ConfigEntry::path).toList()));
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
