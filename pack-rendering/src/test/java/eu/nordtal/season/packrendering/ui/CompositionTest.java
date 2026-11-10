package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

/** Every composed line ends where its geometry says, measured against the assembled pack. */
class CompositionTest {

    @Test
    void aPlateOfAnyWidthAdvancesExactlyThatWidth() {
        for (final Look look : Look.values()) {
            for (int width = 2; width <= 511; width++) {
                assertEquals(
                        width, Advances.of(Pen.start().plate(0, look, width).build()), look + " " + width);
            }
        }
    }

    @Test
    void aChestTitleReturnsToTheAnchorAfterItsWindow() {
        for (int rows = 1; rows <= ChestTitle.MAX_ROWS; rows++) {
            final Component title = ChestTitle.of(rows)
                    .header(
                            Symbol.CHEST,
                            "Lager",
                            List.of(Mark.text("3/9", Colours.SOFT), Mark.symbol(Symbol.PIN, Colours.SOFT)))
                    .slot(0, rows - 1)
                    .plate(1, rows - 1, 7, Look.PRIMARY)
                    .label(1, rows - 1, 7, Symbol.TICK, "Abgeben", Look.PRIMARY)
                    .row(0, 0, 9, "Spawn", "120 m", Look.NORMAL)
                    .build();
            assertEquals(0, Advances.of(title), rows + " rows");
        }
    }

    @Test
    void aContainerTitleDrawsItsPanelAndKeepsTheNameAtTheAnchor() {
        final Component title = ChestTitle.container(3, Component.text("Lager"));

        assertEquals(Letters.width("Lager"), Advances.of(title));
    }

    @Test
    void everyDialogTitleIsTwoHundredFiftyWideWhateverItDraws() {
        for (final DialogPanel panel : DialogPanel.values()) {
            for (final int top : DialogTitle.TOPS) {
                final Component title = DialogTitle.of(panel, top)
                        .header(Symbol.RAIL, "Bahnhof Nordhafen", List.of(Mark.text("2/5", Colours.SOFT)))
                        .centred(150, "Diesen Bahnhof gibt es schon.", Colours.REFUSAL)
                        .build();
                assertEquals(DialogTitle.NET_WIDTH, Advances.of(title), panel + " at " + top);
            }
        }
    }

    private static final BodyLine.Press NEXT = new BodyLine.Press(ClickEvent.suggestCommand("next"), null);
    private static final BodyLine.Press PREVIOUS =
            new BodyLine.Press(ClickEvent.suggestCommand("previous"), Component.text("back"));

    /** The first row of a plate line, whose second row only makes its box two rows high. */
    private static Component art(final Component line) {
        return line.children().get(0);
    }

    @Test
    void bodyLinesAreAsWideAsTheirArt() {
        assertEquals(
                262,
                Advances.of(art(BodyLine.start()
                        .row(242, null, null, "Nordhafen", "8.925 m", Look.NORMAL)
                        .gap(BodyLine.SIDE_GAP)
                        .star(true)
                        .build())));
        assertEquals(
                BodyLine.pagerWidth(2, 5),
                Advances.of(art(BodyLine.start()
                        .pager(2, 5, NEXT, PREVIOUS, "Nächste Seite")
                        .build())));
        assertEquals(
                240,
                Advances.of(art(BodyLine.start()
                        .card(240, Symbol.PICKAXE, "Holzstämme", "1.200/20.000", 0.06, false, Look.NORMAL)
                        .build())));
        assertEquals(
                240,
                Advances.of(art(BodyLine.start()
                        .card(240, Symbol.PICKAXE, "Holzstämme", "Geschafft", 1, true, Look.NORMAL)
                        .build())));
        assertEquals(
                120,
                Advances.of(art(BodyLine.start()
                        .plate(120, null, "Fahren", Look.PRIMARY)
                        .build())));
        assertEquals(98, Advances.of(BodyLine.primaryButton("Speichern")));
        assertEquals(
                248,
                Advances.of(BodyLine.textLine()
                        .text("Siedlung", Colours.INK)
                        .at(200)
                        .text("3/7", Colours.SOFT)
                        .at(248)
                        .build()));
    }

    @Test
    void aPlateLineHasTwoRowsAndATextLineOne() {
        assertTrue(BodyLine.start().star(true).build().children().contains(Component.newline()));
        assertFalse(BodyLine.textLine().text("Kupferberg", Colours.INK).build().children().stream()
                .anyMatch(Component.newline()::equals));
    }

    @Test
    void thePagerDrawsNextFirstSoEnterTurnsThePage() {
        final List<ClickEvent> clicks = new ArrayList<>();
        collectClicks(
                BodyLine.start().pager(1, 3, NEXT, PREVIOUS, "Nächste Seite").build(), clicks);

        assertEquals(List.of(NEXT.click(), PREVIOUS.click()), clicks);
    }

    @Test
    void aButtonCarriesItsClickAndTooltipOverWhatItDraws() {
        final Component line =
                art(BodyLine.start().button(PREVIOUS, part -> part.star(false)).build());
        final Component button = line.children().get(0);

        assertEquals(PREVIOUS.click(), button.clickEvent());
        assertEquals(HoverEvent.showText(Component.text("back")), button.hoverEvent());
        assertEquals(BodyLine.PLATE, Advances.of(line));
    }

    @Test
    void aFieldFrameKeepsTheTitleTwoHundredFiftyWide() {
        assertEquals(
                DialogTitle.NET_WIDTH,
                Advances.of(DialogTitle.of(DialogPanel.STATION_104, 43)
                        .fieldFrame(7, 61)
                        .words("Bahnhof: Gib einen Namen ein.")
                        .build()));
    }

    private static void collectClicks(final Component component, final List<ClickEvent> clicks) {
        final ClickEvent click = component.clickEvent();
        if (click != null && !clicks.contains(click)) {
            clicks.add(click);
        }
        component.children().forEach(child -> collectClicks(child, clicks));
    }

    @Test
    void aPillIsItsContentAndTenWide() {
        assertEquals(Letters.visibleWidth("Händler") + 10, Advances.of(Pill.of("Händler")));
        assertEquals(Pill.width("Händler"), Advances.of(Pill.of("Händler")));
    }

    @Test
    void aTooltipsBoxTakesNoRoomOnItsFirstRow() {
        final Component hover = Tooltip.hover("Zu den Favoriten hinzufügen", null, "Klicken, um zu speichern.");
        final Component first = hover.children().get(0);

        assertEquals(Letters.width("Zu den Favoriten hinzufügen"), Advances.of(first));
        assertEquals(
                2,
                hover.children().stream()
                        .filter(child -> !child.equals(Component.newline()))
                        .count());
    }

    @Test
    void aTooltipBoxIsTwentyHighForOneRowAndTenPerRowAfter() {
        assertEquals(20, Tooltip.boxHeight(1, false));
        assertEquals(32, Tooltip.boxHeight(2, true));
        assertEquals(30, Tooltip.boxHeight(2, false));
    }
}
