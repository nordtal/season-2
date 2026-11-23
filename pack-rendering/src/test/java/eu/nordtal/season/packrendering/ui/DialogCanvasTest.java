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
        assertThrows(IllegalArgumentException.class, () -> canvas.cardPlate(0, 0, 121, Look.DISABLED, click));

        // BodyLine.button itself enforces the guard even when called directly outside DialogCanvas.
        assertThrows(
                IllegalArgumentException.class,
                () -> BodyLine.start().button(click, part -> part.plate(80, null, "Abgeben", Look.DISABLED)));
        assertThrows(
                IllegalArgumentException.class,
                () -> BodyLine.start()
                        .button(click, part -> part.symbolPlate(Symbol.UP, Colours.FAINT, Look.DISABLED)));
        assertThrows(
                IllegalArgumentException.class,
                () -> BodyLine.start()
                        .button(click, part -> part.row(120, null, null, "Hafen", "120 m", Look.DISABLED)));
        assertThrows(
                IllegalArgumentException.class,
                () -> BodyLine.start()
                        .button(
                                click,
                                part -> part.card(120, Symbol.HANDIN, "Eisen", "Geschafft", 1.0, true, Look.DISABLED)));
        assertThrows(
                IllegalArgumentException.class,
                () -> BodyLine.start().button(click, part -> part.cardPlate(121, Look.DISABLED)));
    }

    @Test
    void theMilestoneRailNeverSqueezesFocusesOnCurrentAndNextFadesPastAndFarAndAlwaysShowsLast() {
        // 1 milestone: single current node at x 0.
        final List<MilestoneRail.Node> one = MilestoneRail.nodes(1, 0, 223);
        assertEquals(1, one.size());
        assertEquals(MilestoneRail.State.CURRENT, one.getFirst().state());
        assertFalse(one.getFirst().faded());

        // 7 milestones with current 3: all 7 shown, past faded, current and future not faded.
        final List<MilestoneRail.Node> seven = MilestoneRail.nodes(7, 3, 223);
        assertEquals(
                List.of(0, 1, 2, 3, 4, 5, 6),
                seven.stream().map(MilestoneRail.Node::index).toList());
        assertTrue(seven.get(0).faded());
        assertTrue(seven.get(2).faded());
        assertFalse(seven.get(3).faded());
        assertFalse(seven.get(6).faded());

        // 12 milestones, current 0: 0..4 and last (11) with a dotted gap; 3 and 4 faded, 11 never faded.
        final List<MilestoneRail.Node> twelveFirst = MilestoneRail.nodes(12, 0, 223);
        assertEquals(
                List.of(0, 1, 2, 3, 4, 11),
                twelveFirst.stream().map(MilestoneRail.Node::index).toList());
        assertFalse(twelveFirst.get(0).faded());
        assertFalse(twelveFirst.get(2).faded());
        assertTrue(twelveFirst.get(3).faded());
        assertTrue(twelveFirst.get(4).faded());
        assertFalse(twelveFirst.get(5).faded(), "last milestone is never faded");
        final List<MilestoneRail.Segment> firstSegs = MilestoneRail.segments(twelveFirst, 12, 0);
        assertTrue(firstSegs.getLast().dotted());

        // 12 milestones, current 5 (middle): 0, gap, 5, 6, 7, gap, 11.
        final List<MilestoneRail.Node> twelveMid = MilestoneRail.nodes(12, 5, 223);
        assertEquals(
                List.of(0, 5, 6, 7, 11),
                twelveMid.stream().map(MilestoneRail.Node::index).toList());
        assertTrue(twelveMid.get(0).faded());
        assertFalse(twelveMid.get(1).faded());
        assertFalse(twelveMid.get(3).faded());
        assertFalse(twelveMid.get(4).faded());
        final List<MilestoneRail.Segment> midSegs = MilestoneRail.segments(twelveMid, 12, 5);
        assertTrue(midSegs.getFirst().dotted());
        assertTrue(midSegs.getLast().dotted());

        // 12 milestones, current 11 (last): 0, gap, 7..11.
        final List<MilestoneRail.Node> twelveLast = MilestoneRail.nodes(12, 11, 223);
        assertEquals(
                List.of(0, 7, 8, 9, 10, 11),
                twelveLast.stream().map(MilestoneRail.Node::index).toList());
        assertFalse(twelveLast.getLast().faded());
        assertEquals(MilestoneRail.State.CURRENT, twelveLast.getLast().state());
    }

    @Test
    void theRailCardPlateAndVariableBarComposeOnTheWidePanel() {
        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.WIDE_161)
                .rail(0, 2, 223, 12, 5)
                .cardPlate(0, 24, 121, Look.NORMAL, BodyLine.Press.NONE)
                .darkField(5, 29, ItemPicture.SMALL_FIELD)
                .varBar(32, 43, 50, 0.5, 0.25, false)
                .cardPlate(125, 82, 121, Look.DISABLED, BodyLine.Press.NONE);
        final DialogTitle title =
                DialogTitle.of(DialogPanel.WIDE_161, canvas.panelTop()).header(Symbol.FLAG, "Objectives", List.of());
        final Component framed = canvas.build(FocusRing.Ground.BEVEL_FOOT, title);
        assertEquals(canvas.rows(), Advances.rows(framed).size());
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
