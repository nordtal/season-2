package eu.nordtal.season.smp.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Holds each menu's rows and item slots, as the build writes them into its declarations, against its panel. */
class MenuLayoutsTest {

    @Test
    void theHandInPlacesItsSampleInTheActionRowUnderTheSlots() {
        final DocumentDeclaration handIn = menu("hand-in");

        assertEquals(4, handIn.rows());
        assertEquals(List.of(27), at(handIn, "sample"));
    }

    @Test
    void theGraveDeclaresTwoRowsOfStacksAsItsExample() {
        final DocumentDeclaration grave = menu("grave");

        assertEquals(3, grave.rows());
        assertEquals(List.of(18), at(grave, "owner"));
    }

    @Test
    void theWheelPrizeTakeInIsOneRowThatAnItemIsPutInAnywhere() {
        final DocumentDeclaration takeIn = menu("wheel-take-in");

        assertEquals(1, takeIn.rows());
        assertEquals(IntStream.range(0, 9).boxed().toList(), at(takeIn, "put-in"));
    }

    @Test
    void theDuelKitTakeInIsOneRowThatAnItemIsPutInAnywhere() {
        final DocumentDeclaration takeIn = menu("duel-kit-take-in");

        assertEquals(1, takeIn.rows());
        assertEquals(IntStream.range(0, 9).boxed().toList(), at(takeIn, "put-in"));
    }

    @Test
    void everyMenuPlacesEachItemInsideItsRows() {
        final List<DocumentDeclaration> menus = packaged().values().stream()
                .map(Documents.Packaged::declaration)
                .filter(declaration -> declaration.shape() == DocumentSpec.Shape.MENU)
                .toList();
        assertEquals(9, menus.size());
        for (final DocumentDeclaration menu : menus) {
            assertTrue(menu.rows() >= 1 && menu.rows() <= MenuTitle.MAX_ROWS, menu.id());
            for (final Slot slot : menu.slots()) {
                assertTrue(
                        slot.at().stream().allMatch(at -> at >= 0 && at < menu.rows() * SlotGeometry.COLUMNS),
                        menu.id() + " " + slot.id());
            }
        }
    }

    private static Map<String, Documents.Packaged> packaged() {
        return Documents.packaged(MenuLayoutsTest.class.getClassLoader(), "smp");
    }

    private static DocumentDeclaration menu(final String name) {
        return Objects.requireNonNull(packaged().get(name), name).declaration();
    }

    private static List<Integer> at(final DocumentDeclaration menu, final String slot) {
        return menu.requireSlot(slot).at();
    }
}
