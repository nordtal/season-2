package eu.nordtal.s2.common;

import eu.nordtal.s2.common.hud.BoardFrame;
import eu.nordtal.s2.common.hud.BossBarLine;
import eu.nordtal.s2.common.menu.MenuTitle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentIteratorType;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that nothing composed out of a {@code nordtal:} font is drawn with vanilla's text shadow.
 *
 * <p><b>What the shadow does to a composition.</b> The client draws every glyph a second time, one
 * pixel down and one right, in a darkened copy of its colour. On a line of text that is what text
 * is supposed to look like. On a surface tiled out of power-of-two glyphs butted against each other
 * - the boss bar background, the board's frame, the menu panel - the second copy of tile <i>n</i>
 * lands on top of tile <i>n+1</i>, so a surface the pack drew as one piece arrives with a dark seam
 * at every segment boundary and a dark edge along its bottom and right.
 *
 * <p><b>Why it needs a test rather than a comment.</b> The shadow costs no advance. Every offset in
 * {@link BoardFrame}, {@link MenuTitle} and {@code BossBarWidth} still comes out exactly right, and
 * {@code BoardFrameTest} - which walks the composition with a cursor derived from the pack itself -
 * cannot see the difference. The failure is purely what the pixels look like, which is the one
 * thing nothing in this repository can look at.
 *
 * <p>The readable text is deliberately <em>not</em> covered by this rule: it keeps its shadow, and
 * {@link #titleKeepsItsOwnShadow()} is what pins that the panel does not swallow it. The boss bar
 * is the one exception, and it is an exception on purpose - see the note in the two renderers.
 */
class GlyphShadowTest {

    /** Every source file in this repository that composes a boss bar name. Mirrors BossBarFontTest. */
    private static final String[] BOSS_BAR_SOURCES = {
            "smp/src/main/java/eu/nordtal/s2/smp/hud/SmpHud.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java",
    };

    @Test
    @DisplayName("every frame component a board is built from carries no shadow")
    void boardFrameCarriesNoShadow() {
        final Component board = BoardFrame.render(64,
                Component.text("heading"),
                List.of(Component.text("one"), Component.text("two")));

        assertNoShadowOnFont(board, Glyphs.FONT_BOARD, "BoardFrame.render");
        assertNoShadowOnFont(BoardFrame.border(64, Glyphs.BOARD_CORNER_TOP_LEFT,
                Glyphs.BOARD_CORNER_TOP_RIGHT), Glyphs.FONT_BOARD, "BoardFrame.border");
        assertNoShadowOnFont(BoardFrame.row(64, Component.text("content")),
                Glyphs.FONT_BOARD, "BoardFrame.row");
    }

    @Test
    @DisplayName("the menu panel carries no shadow, at every row count the pack has one for")
    void menuPanelCarriesNoShadow() {
        for (int rows = 1; rows <= Glyphs.GUI_PANELS.length; rows++) {
            assertNoShadowOnFont(MenuTitle.panel(rows), Glyphs.FONT_GUI, "MenuTitle.panel(" + rows + ")");
            assertNoShadowOnFont(MenuTitle.of(rows, Component.text("title")), Glyphs.FONT_GUI,
                    "MenuTitle.of(" + rows + ", ...)");
        }
    }

    @Test
    @DisplayName("a menu's readable title keeps its own shadow")
    void titleKeepsItsOwnShadow() {
        final Component title = Component.text("Wheel", NamedTextColor.GOLD);
        final Component composed = MenuTitle.of(6, title);

        final Component readable = composed.children().stream()
                .filter(child -> !Glyphs.FONT_GUI.equals(keyOf(child)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "MenuTitle.of appended no component outside nordtal:gui - the readable title"
                                + " is gone, or it has become a child of the panel and inherited its"
                                + " shadowless style"));

        assertNull(readable.style().shadowColor(),
                "the readable title must keep vanilla's shadow: the panel is a sibling of it, not"
                        + " its parent, precisely so that turning the panel's shadow off does not"
                        + " flatten the one piece of the window a player actually reads");
    }

    @Test
    @DisplayName("a boss bar line carries no shadow, and the renderers build nothing else")
    void bossBarLineTurnsTheShadowOff() {
        assertNoShadowOnFont(BossBarLine.render(List.of(BossBarLine.Pill.of("x"))),
                Glyphs.FONT_BOSSBAR, "BossBarLine.render");
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = RepositoryRoot.read(source);
            assertTrue(!text.contains("ShadowColor") && !text.contains("Component.text("),
                    source + " styles a boss bar component itself; since 2026-09-05 the one place"
                            + " that happens is BossBarLine, so the shadow is off everywhere or"
                            + " nowhere. BossBarFontTest pins that every name goes through it.");
        }
    }

    @Test
    @DisplayName("a menu canvas with overlays carries no shadow either")
    void menuCanvasCarriesNoShadow() {
        final Component surface = MenuTitle.on(Glyphs.GUI_TRAVEL_PANEL)
                .overlay(Glyphs.GUI_TRAVEL_LOCKED_BOTTOM, 99, 68)
                .overlay(Glyphs.GUI_TRAVEL_HERE_TOP, 9, 68)
                .build(Component.text("title"));
        assertNoShadowOnFont(surface, Glyphs.FONT_GUI, "MenuTitle.Canvas.build");
    }

    /**
     * Walks {@code root} and every descendant, and asserts that each component naming {@code font}
     * sets {@link ShadowColor#none()}.
     *
     * <p>It checks the component's <em>own</em> style rather than a resolved one on purpose: a
     * shadow that is only absent because a parent happened to turn it off is one refactor away from
     * coming back.
     */
    private static void assertNoShadowOnFont(final Component root, final String font, final String what) {
        int seen = 0;
        for (final Component component : root.iterable(ComponentIteratorType.DEPTH_FIRST)) {
            if (!font.equals(keyOf(component))) {
                continue;
            }
            seen++;
            assertEquals(ShadowColor.none(), component.style().shadowColor(),
                    what + " emits a component in " + font + " that does not set"
                            + " ShadowColor.none(), so its tiles bleed into each other");
        }
        assertTrue(seen > 0, what + " emitted no component in " + font + " at all - either the font"
                + " key moved or this test is asserting nothing");
    }

    private static String keyOf(final Component component) {
        return component.style().font() == null ? null : component.style().font().asString();
    }
}
