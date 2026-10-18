package eu.nordtal.season.messages.spec.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.text.Declaration;
import eu.nordtal.season.messages.value.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What layouts.json says of a layout beyond its declaration, and the building blocks that follow from it. */
class LayoutSchemaTest {

    private static final Function<String, Optional<MessageSchema.Bundle>> SCHEMAS =
            LayoutSchema.schemas(List.of(LayoutTexts.class));

    private static final Layout STEPS =
            Layout.discord("layouts", "steps", "Link", List.of(), Part.text(Line.text("layouts/steps")));

    private static LayoutSchema.Entry described(final Layout layout) {
        return LayoutSchema.describe(layout, SCHEMAS, problem -> {
            throw new AssertionError(problem);
        });
    }

    @Test
    void aLayoutsValuesAreWhatItsTextsAndTheGlobalsNameButNotWhatADataListsItemsFill() {
        final Declaration values = described(LayoutCheckTest.PRICES).values();

        assertEquals(Kind.MONEY, values.values().get("amount"));
        assertFalse(values.values().containsKey("days"));
        assertTrue(values.values().keySet().stream().anyMatch(name -> name.startsWith("server.")));
        assertEquals(Set.of(), values.roles());
    }

    @Test
    void aSelectsOptionLineIsFilledByEachOptionAndNotByTheLayout() {
        final Layout pick = Layout.discord(
                "layouts",
                "pick",
                "Link",
                List.of(Element.select("language", "layouts/choose", "layouts/language")),
                Part.row("language"));

        assertFalse(described(pick).values().values().containsKey("language"));
    }

    @Test
    void aLayoutShowsEveryKeyItsMessageValuesMayTake() {
        assertEquals(List.of("layouts/steps", "layouts/buy"), described(STEPS).shows());
    }

    @Test
    void aMessageValueThatTakesAWholeBundleShowsEveryKeyOfIt() {
        final Layout any = Layout.discord("layouts", "any", "Link", List.of(), Part.text(Line.text("layouts/any")));

        assertEquals(
                MessageSchema.entries(LayoutTexts.class).stream()
                        .map(entry -> "layouts/" + entry.key())
                        .sorted()
                        .toList(),
                described(any).shows().stream().sorted().toList());
    }

    @Test
    void aKeyShownInTwoLayoutsIsABuildingBlockOfBoth() {
        assertEquals(
                Map.of("layouts/buy", List.of("layouts/prices", "layouts/steps")),
                LayoutSchema.buildingBlocks(List.of(described(LayoutCheckTest.PRICES), described(STEPS))));
    }

    @Test
    void layoutsJsonReadsBackAsItWasWritten() {
        final List<LayoutSchema.Entry> entries = List.of(described(LayoutCheckTest.PRICES), described(STEPS));

        assertEquals(entries, LayoutSchema.decode(LayoutSchema.json(entries)));
    }

    @Test
    void theSchemaTaskWritesEachBundlesLayoutsBesideItsSchema(@TempDir final Path output) throws Exception {
        MessageSchema.main(new String[] {
            output.toString(), LayoutTexts.class.getName(), "--layouts", LayoutCheckTest.class.getName()
        });

        assertTrue(Files.exists(output.resolve("messages/layouts/schema.json")));
        assertEquals(
                List.of(described(LayoutCheckTest.PRICES), described(LayoutCheckTest.CODE)),
                LayoutSchema.decode(Files.readString(output.resolve("messages/layouts/layouts.json"))));
    }

    @Test
    void aLayoutListedUnderABundleTheModuleDoesNotShipIsRefused(@TempDir final Path output) {
        final IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> LayoutSchema.write(output, List.of(), List.of(LayoutCheckTest.PRICES)));

        assertTrue(
                refused.getMessage().contains("is listed under layouts, which none of the module's specs ships"),
                refused.getMessage());
    }
}
