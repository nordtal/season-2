package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.kyori.adventure.text.Component;
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

    @Test
    void bodyLinesAreAsWideAsTheirArt() {
        assertEquals(
                262,
                Advances.of(BodyLine.start()
                        .row(242, null, null, "Nordhafen", "8.925 m", Look.NORMAL)
                        .gap(BodyLine.SIDE_GAP)
                        .star(true)
                        .build()));
        assertEquals(
                BodyLine.pagerWidth(2, 5),
                Advances.of(BodyLine.start().pager(2, 5).build()));
        assertEquals(
                240,
                Advances.of(BodyLine.start()
                        .card(240, Symbol.PICKAXE, "Holzstämme", "1.200/20.000", 0.06, false, Look.NORMAL)
                        .build()));
        assertEquals(
                240,
                Advances.of(BodyLine.start()
                        .card(240, Symbol.PICKAXE, "Holzstämme", "Geschafft", 1, true, Look.NORMAL)
                        .build()));
        assertEquals(
                120,
                Advances.of(BodyLine.start()
                        .plate(120, null, "Fahren", Look.PRIMARY)
                        .build()));
        assertEquals(98, Advances.of(BodyLine.primaryButton("Speichern")));
        assertEquals(184, Advances.of(BodyLine.fieldFrame()));
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
