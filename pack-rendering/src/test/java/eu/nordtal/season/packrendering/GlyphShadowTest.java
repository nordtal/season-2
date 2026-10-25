package eu.nordtal.season.packrendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.board.BoardFrame;
import eu.nordtal.season.packrendering.hud.BossBarLine;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import org.junit.jupiter.api.Test;

/**
 * Asserts that nothing composed from a {@code nordtal:} font is drawn with the vanilla text shadow.
 *
 * The shadow adds no advance and seams every tile boundary, which only the pixels show. The boss bar is exempt.
 */
class GlyphShadowTest {

    @Test
    void everyFrameComponentABoardIsBuiltFromCarriesNoShadow() {
        final Component board =
                BoardFrame.render(64, Component.text("heading"), List.of(Component.text("one"), Component.text("two")));

        assertNoShadowOnFont(board, Glyphs.FONT_BOARD, "BoardFrame.render");
        assertNoShadowOnFont(
                BoardFrame.border(64, Glyphs.BOARD_CORNER_TOP_LEFT, Glyphs.BOARD_CORNER_TOP_RIGHT),
                Glyphs.FONT_BOARD,
                "BoardFrame.border");
        assertNoShadowOnFont(BoardFrame.row(64, Component.text("content")), Glyphs.FONT_BOARD, "BoardFrame.row");
    }

    @Test
    void theMenuPanelCarriesNoShadowAtEveryRowCountThePackHasOneFor() {
        for (int rows = 1; rows <= Glyphs.GUI_PANELS.size(); rows++) {
            assertNoShadowOnFont(MenuTitle.panel(rows), Glyphs.FONT_GUI, "MenuTitle.panel(" + rows + ")");
            assertNoShadowOnFont(
                    MenuTitle.of(rows, Component.text("title")), Glyphs.FONT_GUI, "MenuTitle.of(" + rows + ", ...)");
        }
    }

    @Test
    void aMenusReadableTitleKeepsItsOwnShadow() {
        final Component title = Component.text("Wheel", NamedTextColor.GOLD);
        final Component composed = MenuTitle.of(6, title);

        final Component readable = composed.children().stream()
                .filter(child -> !Glyphs.FONT_GUI.equals(keyOf(child)))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("MenuTitle.of appended no component outside nordtal:gui - the readable title"
                                + " is gone, or it has become a child of the panel and inherited its"
                                + " shadowless style"));

        assertNull(
                readable.style().shadowColor(),
                "the readable title must keep vanilla's shadow: the panel is a sibling of it, not"
                        + " its parent, precisely so that turning the panel's shadow off does not"
                        + " flatten the one piece of the window a player actually reads");
    }

    /** The rule {@code :architecture} enforces is that no renderer names a boss bar any other way. */
    @Test
    void aBossBarLineCarriesNoShadow() {
        assertNoShadowOnFont(
                BossBarLine.render(List.of(BossBarLine.Pill.of("x"))), Glyphs.FONT_BOSSBAR, "BossBarLine.render");
    }

    @Test
    void aMenuCanvasWithOverlaysCarriesNoShadowEither() {
        final Component surface = MenuTitle.on(Glyphs.GUI_PANEL_6)
                .overlay(Glyphs.GUI_CARD_BOTTOM, 99, 68)
                .overlay(Glyphs.GUI_CARD_TOP, 9, 68)
                .build(Component.text("title"));
        assertNoShadowOnFont(surface, Glyphs.FONT_GUI, "MenuTitle.Canvas.build");
    }

    private static void assertNoShadowOnFont(final Component root, final String font, final String what) {
        int seen = 0;
        for (final Component component : root.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (!font.equals(keyOf(component))) {
                continue;
            }
            seen++;
            assertEquals(
                    ShadowColor.none(),
                    component.style().shadowColor(),
                    what + " emits a component in " + font + " that does not set"
                            + " ShadowColor.none(), so its tiles bleed into each other");
        }
        assertTrue(
                seen > 0,
                what + " emitted no component in " + font + " at all - either the font"
                        + " key moved or this test is asserting nothing");
    }

    private static String keyOf(final Component component) {
        return component.style().font() == null
                ? null
                : component.style().font().asString();
    }
}
