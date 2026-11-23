package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

/** Tests free placement, card grids, item pictures, scroll columns and disabled controls on the dialog canvas. */
class DialogCanvasTest {

    private static final ClickEvent UP = ClickEvent.suggestCommand("up");
    private static final ClickEvent DOWN = ClickEvent.suggestCommand("down");
    private static final ClickEvent PAGE_UP = ClickEvent.suggestCommand("page_up");
    private static final ClickEvent PAGE_DOWN = ClickEvent.suggestCommand("page_down");

    @Test
    void theWideAndCompactPanelsFitTheirFullContentAndLandOnTheBlackBorder() {
        for (final DialogPanel panel : List.of(DialogPanel.WIDE_161, DialogPanel.COMPACT_116)) {
            final DialogCanvas canvas = DialogCanvas.of(panel);
            final DialogTitle title = DialogTitle.of(panel, canvas.panelTop()).header(Symbol.MEDAL, "Ziele", List.of());
            final Component framed = canvas.build(FocusRing.Ground.BEVEL_FOOT, title);

            assertEquals(DialogTitle.NET_WIDTH, Advances.of(title.build()), panel + " title");
            assertEquals(panel.contentWidth(), canvas.width(), panel + " content width");
            assertEquals(panel.contentHeight(), canvas.height(), panel + " content height");
            final int boxTop = BodyColumn.PANEL_TO_CONTENT - canvas.top();
            assertEquals(panel.height() - 1, boxTop + 9 * canvas.rows() + 7, panel + " box bottom");
            assertEquals(canvas.rows(), Advances.rows(framed).size(), panel + " framed rows");
        }
    }

    @Test
    void aSingleRowFocusRingFitsInOneLineAndAdvancesToTheBoxWidth() {
        for (final FocusRing.Ground ground : FocusRing.Ground.values()) {
            final Component framed = FocusRing.frame(Pen.start().move(60).build(), 60, FocusRing.SINGLE_ROW, ground);
            assertEquals(List.of(FocusRing.box(60) - 8), Advances.rows(framed), ground.name());
        }
    }

    @Test
    void aTwoColumnCardGridUsesTheScrollColumnsRoomWhenNotScrollableAndKeepsOddLastLeft() {
        final CardGrid plain = CardGrid.of(0, 24, DialogPanel.WIDE_161.contentWidth(), 54, 3, false);
        assertEquals(266, plain.cardsWidth());
        assertEquals(131, plain.cardWidth());
        final List<CardGrid.Slot> three = plain.visible(3, 0, 2);
        assertEquals(3, three.size());
        assertEquals(0, three.get(2).column());
        assertEquals(0, three.get(2).x());
        assertEquals(24 + 57, three.get(2).y());

        final CardGrid scrolled = CardGrid.of(0, 24, DialogPanel.WIDE_161.contentWidth(), 54, 3, true);
        assertEquals(246, scrolled.cardsWidth());
        assertEquals(121, scrolled.cardWidth());
        assertEquals(249, scrolled.scrollColumnX());
        final List<CardGrid.Slot> pageTwo = scrolled.visible(5, 1, 2);
        assertEquals(3, pageTwo.size());
        assertEquals(2, pageTwo.get(0).index());
        assertEquals(4, pageTwo.get(2).index());
        assertEquals(0, pageTwo.get(2).column());
    }

    @Test
    void anItemPictureFallsBackToASymbolWhenNoAtlasSpriteExists() {
        final ItemPicture missing = ItemPicture.of("minecraft:no_such_item", Symbol.PICKAXE, Colours.ACCENT);
        assertFalse(missing.hasSprite());
        assertEquals(Symbol.ADVANCE, missing.advance());

        final ItemPicture diamond = ItemPicture.of("minecraft:diamond", Symbol.PICKAXE, Colours.ACCENT);
        assertTrue(diamond.hasSprite());
        assertEquals(ItemPicture.SPRITE_ADVANCE, diamond.advance());
    }

    @Test
    void disabledControlsRefuseClickCallbacks() {
        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.WIDE_161);
        final BodyLine.Press click = new BodyLine.Press(UP, null);
        assertThrows(
                IllegalArgumentException.class, () -> canvas.plate(0, 0, 80, null, "Abgeben", Look.DISABLED, click));
        assertThrows(
                IllegalArgumentException.class,
                () -> canvas.symbolPlate(0, 0, Symbol.UP, Colours.FAINT, Look.DISABLED, click, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> canvas.row(0, 0, 120, null, null, "Hafen", "120 m", Look.DISABLED, click));
    }

    @Test
    void theScrollColumnOmitsCallbacksOnDisabledArrowsAndThePositionStrip() {
        final ScrollState atTop = ScrollState.of(6, 2, 0);
        assertFalse(atTop.canScrollUp());
        assertTrue(atTop.canScrollDown());
        final Component topBuilt = DialogCanvas.of(DialogPanel.WIDE_161)
                .scrollColumn(249, 24, atTop, UP, DOWN, PAGE_UP, PAGE_DOWN, "Up", "Down")
                .build(FocusRing.Ground.BEVEL_FOOT);
        final List<ClickEvent> topClicks = new ArrayList<>();
        collectClicks(topBuilt, topClicks);
        assertFalse(topClicks.contains(UP), "disabled up arrow must carry no callback");
        assertFalse(topClicks.contains(PAGE_UP), "no track space above the strip at top");
        assertTrue(topClicks.contains(DOWN));
        assertTrue(topClicks.contains(PAGE_DOWN));

        final ScrollState atBottom = ScrollState.of(6, 2, 4);
        assertTrue(atBottom.canScrollUp());
        assertFalse(atBottom.canScrollDown());
        final Component bottomBuilt = DialogCanvas.of(DialogPanel.WIDE_161)
                .scrollColumn(249, 24, atBottom, UP, DOWN, PAGE_UP, PAGE_DOWN, "Up", "Down")
                .build(FocusRing.Ground.BEVEL_FOOT);
        final List<ClickEvent> bottomClicks = new ArrayList<>();
        collectClicks(bottomBuilt, bottomClicks);
        assertTrue(bottomClicks.contains(UP));
        assertTrue(bottomClicks.contains(PAGE_UP));
        assertFalse(bottomClicks.contains(DOWN), "disabled down arrow must carry no callback");
        assertFalse(bottomClicks.contains(PAGE_DOWN), "no track space below the strip at bottom");
    }

    private static void collectClicks(final Component component, final List<ClickEvent> clicks) {
        final ClickEvent click = component.clickEvent();
        if (click != null && !clicks.contains(click)) {
            clicks.add(click);
        }
        component.children().forEach(child -> collectClicks(child, clicks));
    }
}
