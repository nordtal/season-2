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
    void theWheelPlacesItsHubBesideTheRingAndItsAgainButtonUnderTheRules() {
        final DocumentDeclaration wheel = menu("wheel");

        assertEquals(6, wheel.rows());
        assertEquals(List.of(29, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44), at(wheel, "hub"));
        assertEquals(List.of(50, 51, 52, 53), at(wheel, "again"));
    }

    @Test
    void theHandInPlacesItsSampleAndItsConfirmButtonUnderTheTray() {
        final DocumentDeclaration handIn = menu("hand-in");

        assertEquals(4, handIn.rows());
        assertEquals(List.of(27), at(handIn, "sample"));
        assertEquals(List.of(33, 34, 35), at(handIn, "confirm"));
    }

    @Test
    void theGraveDeclaresTwoRowsOfStacksAsItsExample() {
        final DocumentDeclaration grave = menu("grave");

        assertEquals(3, grave.rows());
        assertEquals(List.of(18), at(grave, "owner"));
        assertEquals(List.of(19, 20, 21, 22), at(grave, "experience"));
        assertEquals(List.of(23, 24, 25, 26), at(grave, "take-all"));
    }

    @Test
    void theNavigationDeclaresAFullPageBetweenTwoPagesAsItsExample() {
        final DocumentDeclaration navigate = menu("navigate");

        assertEquals(6, navigate.rows());
        assertEquals(IntStream.range(0, 45).boxed().toList(), at(navigate, "target"));
        assertEquals(List.of(45, 46, 47), at(navigate, "stop"));
        assertEquals(List.of(51), at(navigate, "previous-page"));
        assertEquals(List.of(53), at(navigate, "next-page"));
    }

    @Test
    void theObjectivesDeclareFourCardsBetweenTwoPagesAsTheirExample() {
        final DocumentDeclaration objective = menu("objectives");

        assertEquals(6, objective.rows());
        assertEquals(IntStream.range(0, 9).boxed().toList(), at(objective, "heading"));
        assertEquals(
                List.of(
                        9, 10, 11, 12, 18, 19, 20, 21, 14, 15, 16, 17, 23, 24, 25, 26, 27, 28, 29, 30, 36, 37, 38, 39,
                        32, 33, 34, 35, 41, 42, 43, 44),
                at(objective, "objective"));
        assertEquals(IntStream.range(45, 54).boxed().toList(), at(objective, "share"));
        assertEquals(List.of(52), at(objective, "previous-page"));
        assertEquals(List.of(53), at(objective, "next-page"));
    }

    @Test
    void everyMenuPlacesEachItemInsideItsRows() {
        final List<DocumentDeclaration> menus = packaged().values().stream()
                .map(Documents.Packaged::declaration)
                .filter(declaration -> declaration.shape() == DocumentSpec.Shape.MENU)
                .toList();
        assertEquals(5, menus.size());
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
