package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.jspecify.annotations.Nullable;
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
                    .symbol(44, 0, Symbol.POINTER, Colours.INK)
                    .centred(5, rows - 1, 4, "Noch 2 Spins", Colours.SOFT)
                    .build();
            assertEquals(0, Advances.of(title), rows + " rows");
        }
    }

    @Test
    void aBoldChestTitleWithAFivePixelMarkReturnsToTheAnchor() {
        for (int rows = 1; rows <= ChestTitle.MAX_ROWS; rows++) {
            final Component title = ChestTitle.of(rows)
                    .boldHeader(Symbol.GRAVE, "Grave", List.of(Mark.small("+1,395 XP", Colours.SOFT)))
                    .plate(4, rows - 1, 5, Look.PRIMARY)
                    .label(4, rows - 1, 5, null, "Take all", Look.PRIMARY)
                    .build();
            assertEquals(0, Advances.of(title), rows + " rows");
        }
    }

    @Test
    void aContainerTitleDrawsItsPanelAndKeepsTheNameAtTheAnchor() {
        final Component title = ChestTitle.container(3, Component.text("Lager"));

        assertEquals(160, ChestTitle.CONTAINER_ROOM);
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

    @Test
    void thePagerIsEvenSoItCentresOnAWholePixel() {
        for (int pages = 1; pages <= 120; pages++) {
            for (int page = 1; page <= pages; page++) {
                assertEquals(0, BodyLine.pagerWidth(page, pages) % 2, page + "/" + pages);
            }
        }
    }

    @Test
    void aColumnStandsItsRowsThreeApartAndEndsFourteenAboveTheNextLine() {
        final BodyColumn followed = BodyColumn.of(262, true).rows(5, 5, index -> line -> line.gap(262));
        assertEquals(5 * BodyLine.PLATE + 4 * BodyLine.SIDE_GAP, followed.height());
        final int box = 9 * followed.rows() + 8;
        final int nextArt = box + 10 + BodyColumn.AFTER;
        assertEquals(BodyColumn.LINE_GAP, nextArt - followed.top() - followed.height());
        final BodyColumn last =
                BodyColumn.of(262, false).rows(1, 0, index -> line -> line.plate(262, null, "Fahren", Look.NORMAL));
        assertEquals(11, last.top());
        assertEquals(3, last.rows());
        assertEquals(List.of(0, 264, 0), Advances.rows(last.build(FocusRing.Ground.BEVEL)));
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
                Advances.of(BodyLine.start()
                        .pager(2, 5, NEXT, PREVIOUS, "Nächste Seite")
                        .build()));
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
        assertEquals(
                BodyLine.FACE_FRAME, Advances.of(BodyLine.start().faceFrame().build()));
        assertEquals(
                BodyLine.ACCENT_FRAME,
                Advances.of(BodyLine.start().accentFrame().build()));
    }

    @Test
    void aNativeButtonLabelAdvancesNothingSoTheClientCentresItsRimOnTheButton() {
        // Any advance would let the client scroll a wide label and clip the rim.
        assertEquals(0, Advances.of(BodyLine.primaryButton("Speichern")));
        assertEquals(0, Advances.of(BodyLine.normalButton("Schließen")));
        assertEquals(0, Advances.of(BodyLine.normalButton("Schließen und zur Übersicht zurückkehren")));
    }

    @Test
    void aFramedLineCentresItsRingOnTheBoxAndKeepsTheBoxWide() {
        for (final FocusRing.Ground ground : FocusRing.Ground.values()) {
            for (int rows = FocusRing.MIN_ROWS; rows <= 14; rows++) {
                for (int art = 8; art <= 300; art++) {
                    final Component framed =
                            FocusRing.frame(Pen.start().move(art).build(), art, rows, ground);
                    final List<Integer> expected = new ArrayList<>(List.of(0, art + FocusRing.MARGIN - 8));
                    while (expected.size() < rows) {
                        expected.add(0);
                    }
                    assertEquals(expected, Advances.rows(framed), ground + " " + rows + " rows, art " + art);
                }
            }
        }
    }

    @Test
    void aRingCoversTheWholeOutlineAndKeepsThePanelsBlackBorderUnderAFootLine() {
        final List<String> ring = Glyphs.UI_RING_26;
        for (final FocusRing.Ground ground : FocusRing.Ground.values()) {
            final String drawn = text(FocusRing.frame(Pen.start().move(100).build(), 100, 2, ground));
            final boolean bevel = ground == FocusRing.Ground.BEVEL || ground == FocusRing.Ground.BEVEL_FOOT;
            final boolean foot = ground == FocusRing.Ground.PANEL_FOOT || ground == FocusRing.Ground.BEVEL_FOOT;
            assertEquals(bevel || foot ? 0 : 2, count(drawn, ring.get(0)), ground + " sides");
            assertEquals(!bevel && foot ? 2 : 0, count(drawn, ring.get(30)), ground + " sides over the foot");
            assertEquals(bevel && foot ? 1 : 0, count(drawn, ring.get(31)), ground + " bevel over the foot");
            assertEquals(bevel && !foot, drawn.contains(ring.get(1)), ground + " shade");
            assertEquals(bevel && foot, drawn.contains(ring.get(2)), ground + " shade foot");
            // The box is 110 wide; on the bevel its left column is white, where the outline cannot be seen.
            assertEquals(bevel ? 108 : 110, columns(drawn, ring.subList(3, 12)), ground + " top runs");
            assertEquals(foot ? 0 : bevel ? 108 : 110, columns(drawn, ring.subList(12, 21)), ground + " bottom runs");
            assertEquals(foot ? (bevel ? 108 : 110) : 0, columns(drawn, ring.subList(21, 30)), ground + " foot runs");
        }
    }

    @Test
    void theFirstColumnDrawsTheHeaderAgainWhereItsCoverLiesOnIt() {
        final List<DialogPanel> panels = DialogPanel.RIDE_LIST_ROWS;
        for (final DialogPanel panel : panels) {
            final int count = (panel.height() - 22) / BodyColumn.STEP;
            final BodyColumn column = BodyColumn.of(panel.width() - 16, false)
                    .rows(count, 0, index -> line -> line.button(PREVIOUS, part -> part.star(true)));
            final DialogTitle title =
                    DialogTitle.of(panel, column.panelTop()).header(Symbol.RAIL, "Fahrtziel", List.of());
            final String drawn = text(column.build(FocusRing.Ground.BEVEL_FOOT, title));
            final int boxTop = BodyColumn.PANEL_TO_CONTENT - column.top();
            final boolean onInk = boxTop >= DialogTitle.MARGIN && boxTop <= DialogTitle.MARGIN + 7;
            assertEquals(onInk, drawn.contains("Fahrtziel"), panel + ", box top on panel row " + boxTop);
            assertEquals(onInk, drawn.contains(Symbol.RAIL.glyph()), panel + ", the symbol");
            assertEquals(onInk, columns(drawn, Glyphs.UI_RULE) > 0, panel + ", the rule");
        }
    }

    @Test
    void aLastColumnEndsItsBoxOnThePanelsBlackBorder() {
        final List<DialogPanel> panels = DialogPanel.RIDE_LIST_ROWS;
        for (final DialogPanel panel : panels) {
            final int count = (panel.height() - 22) / BodyColumn.STEP;
            final BodyColumn column = BodyColumn.of(panel.width() - 16, false)
                    .rows(
                            count,
                            0,
                            index -> line -> line.button(
                                            PREVIOUS,
                                            part -> part.row(
                                                    panel.width() - 36, null, null, "Hafen", "120 m", Look.NORMAL))
                                    .gap(BodyLine.SIDE_GAP)
                                    .button(NEXT, part -> part.star(true)));
            final DialogTitle title = DialogTitle.of(panel, column.panelTop()).header(Symbol.RAIL, "Fahrt", List.of());
            column.build(FocusRing.Ground.BEVEL_FOOT, title);
            title.build();

            final int boxTop = BodyColumn.PANEL_TO_CONTENT - column.top();
            assertEquals(panel.height() - 1, boxTop + 9 * column.rows() + 7, panel + " box bottom");
        }
    }

    /** The columns {@code drawn} covers with the runs of {@code runs}, which run 1, 2, 4 to 256 wide. */
    private static int count(final String drawn, final String glyph) {
        return drawn.split(Pattern.quote(glyph), -1).length - 1;
    }

    private static int columns(final String drawn, final List<String> runs) {
        int columns = 0;
        for (int index = 0; index < runs.size(); index++) {
            columns += (1 << index) * (drawn.split(Pattern.quote(runs.get(index)), -1).length - 1);
        }
        return columns;
    }

    private static String text(final Component component) {
        final StringBuilder out = new StringBuilder();
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> out.append(text(child)));
        return out.toString();
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
                BodyLine.start().button(PREVIOUS, part -> part.star(false)).build();
        final Component button = line.children().get(0);

        assertEquals(PREVIOUS.click(), button.clickEvent());
        assertEquals(HoverEvent.showText(Component.text("back")), button.hoverEvent());
        assertEquals(BodyLine.PLATE, Advances.of(line));
    }

    @Test
    void aHeaderWithItsRuleKeepsTheTitleTwoHundredFiftyWide() {
        assertEquals(
                DialogTitle.NET_WIDTH,
                Advances.of(DialogTitle.of(DialogPanel.STATION_92, 43)
                        .header(Symbol.RAIL, "Bahnhof", List.of())
                        .text(DialogTitle.MARGIN, 119, "Gib einen Namen ein.", Colours.REFUSAL)
                        .build()));
    }

    @Test
    void aStationMapToggleNarratesItsStateAndAdvancesNothing() {
        final Component shown = BodyLine.stationMapToggle(true, "Shown on the map", "Hidden from the map");
        final Component hidden = BodyLine.stationMapToggle(false, "Shown on the map", "Hidden from the map");

        assertTrue(text(shown).contains("Shown on the map"));
        assertTrue(text(hidden).contains("Hidden from the map"));
        assertEquals(0, Advances.of(shown));
        assertEquals(0, Advances.of(hidden));
    }

    @Test
    void aButtonOverTheFieldAdvancesNothingSoTheClientCentresItsPlate() {
        assertEquals(0, Advances.of(BodyLine.primaryButtonOverField("Speichern")));
    }

    private static void collectClicks(final Component component, final List<ClickEvent> clicks) {
        final ClickEvent click = component.clickEvent();
        if (click != null && !clicks.contains(click)) {
            clicks.add(click);
        }
        component.children().forEach(child -> collectClicks(child, clicks));
    }

    @Test
    void thePagersWordsFollowItsFirstArtSinceLeadingWordsWrapTheLine() {
        final Component line = BodyLine.start()
                .pager(1, 3, NEXT, PREVIOUS, "Nächste Seite, Seite 1 von 3")
                .build();

        assertNotEquals(Key.key(Glyphs.FONT_UI_SILENT), firstFont(line, Key.key("default")));
    }

    private static @Nullable Key firstFont(final Component component, final Key inherited) {
        final Key font = component.font() == null ? inherited : component.font();
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            return font;
        }
        for (final Component child : component.children()) {
            final Key found = firstFont(child, font);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aPillIsItsContentAndTenWide() {
        assertEquals(Letters.visibleWidth("Händler") + 10, Advances.of(Pill.of("Händler")));
        assertEquals(Pill.width("Händler"), Advances.of(Pill.of("Händler")));
        assertEquals(Pill.width(Symbol.RAIL, "Nordwacht"), Advances.of(Pill.of(Symbol.RAIL, "Nordwacht")));
        assertEquals(
                Pill.width(Symbol.GRAVE, "Verschwindet in 4 min"),
                Advances.of(Pill.of(
                        Symbol.GRAVE,
                        List.of(new Pill.Run("Verschwindet in ", Colours.SOFT), new Pill.Run("4 min", Colours.INK)))));
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
