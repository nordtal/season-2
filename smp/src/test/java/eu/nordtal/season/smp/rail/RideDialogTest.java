package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.ui.BodyColumn;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.DialogCanvas;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.FocusRing;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/** Verifies the ride map and ride list panels, layout geometry, hidden destination unpinning and font references. */
class RideDialogTest {

    private static final AppliedDocuments DOCUMENTS =
            new AppliedDocuments(RideDialogTest.class.getClassLoader(), Set.of("smp"));

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(DOCUMENTS);

    private static final RideDialog.Answers NOOP = new RideDialog.Answers() {
        @Override
        public void ride(final Player player, final UUID station, final RideDialog.State state) {}

        @Override
        public void star(final Player player, final UUID station, final RideDialog.State state) {}

        @Override
        public void select(final Player player, final UUID station, final RideDialog.State state) {}

        @Override
        public void update(final Player player, final RideDialog.State state) {}
    };

    private static GameDocument rideDocument() {
        return new GameDocument(
                RENDERER, DOCUMENTS.applied(RideDialog.DOCUMENT), Viewer.of(Locale.ENGLISH), new DocumentSpec.None());
    }

    private static Dialogs.Send fakeSend() {
        return new Dialogs(Runnable::run, (player, feedback) -> {}).send();
    }

    @Test
    void theRideDialogPanelsHaveExact280PxWidthsAndFitContent() {
        assertEquals(280, DialogPanel.RIDE_LIST_33.width());
        assertEquals(280, DialogPanel.RIDE_LIST_146.width());
        assertEquals(280, DialogPanel.WIDE_161.width());
        assertEquals(DialogPanel.RIDE_LIST_146.contentWidth(), RideDialog.WIDTH);
        assertEquals(DialogPanel.WIDE_161.contentWidth(), RideDialog.WIDTH);
        assertEquals(DialogPanel.WIDE_161.contentHeight(), RideDialog.CONTROLS_Y + BodyLine.PLATE);
    }

    @Test
    void aThirtyTwoCharacterStationNameAndFiveDigitDistanceFitInOneRow() {
        final String longest = "Bernsteinbucht am Nordfjordhafen";
        assertEquals(32, longest.length());
        final int room = BodyLine.rowRoom(RideDialog.ROW, false, "12,400 m");
        assertTrue(Letters.visibleWidth(longest) <= room, "room " + room + " for " + Letters.visibleWidth(longest));
    }

    @Test
    void thePagedCanvasEndsItsBoxOnThePanelsBlackFootBorder() {
        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.RIDE_LIST_146);
        final int boxTop = BodyColumn.PANEL_TO_CONTENT - canvas.top();
        final int boxHeight = 9 * canvas.rows() + 8;

        assertEquals(DialogPanel.RIDE_LIST_146.height(), boxTop + boxHeight);
        assertTrue(DialogTitle.TOPS.contains(canvas.panelTop()));
    }

    @Test
    void theMapCanvasFitsWide161AndLeavesHiddenDestinationsUnpinned() {
        final RailPos origin = new RailPos(400, 64, 400);
        final RailPos junction = new RailPos(560, 64, 400);
        final RailPos mid = new RailPos(720, 64, 400);
        final RailPos dest = new RailPos(880, 64, 400);
        final UUID originId = new UUID(0, 1);
        final UUID midId = new UUID(0, 2);
        final UUID destId = new UUID(0, 3);
        final List<StationRow> stations = List.of(
                new StationRow(originId, "world", origin.x(), 64, origin.z(), "EAST_WEST", null, null, null),
                new StationRow(
                        midId, "world", mid.x(), 64, mid.z(), "EAST_WEST", UUID.randomUUID(), "Market", Instant.EPOCH),
                new StationRow(
                        destId,
                        "world",
                        dest.x(),
                        64,
                        dest.z(),
                        "EAST_WEST",
                        UUID.randomUUID(),
                        "Harbour",
                        Instant.EPOCH));
        final RailGraph graph = new RailGraph(
                List.of(origin, mid, dest),
                List.of(junction),
                List.of(
                        RailEdge.of(origin, junction, List.of(new RailPos(480, 64, 400))),
                        RailEdge.of(junction, mid, List.of(new RailPos(640, 64, 400))),
                        RailEdge.of(mid, dest, List.of(new RailPos(800, 64, 400))),
                        RailEdge.of(junction, new RailPos(560, 64, 560), List.of(new RailPos(560, 64, 480)))));
        final List<RideList.Stop> stops = List.of(
                new RideList.Stop(destId, "Harbour", 480, true), new RideList.Stop(midId, "Market", 320, false));

        final RideDialog.Context visibleCtx = new RideDialog.Context(graph, origin, stations, Set.of(), stops, "Spawn");
        final DialogCanvas visibleCanvas =
                RideDialog.buildMapCanvas(rideDocument(), visibleCtx, RideDialog.State.initial(), fakeSend(), NOOP);
        final Component visibleBuilt = visibleCanvas.build(FocusRing.Ground.BEVEL_FOOT);
        assertTrue(containsGlyph(visibleBuilt, Glyphs.UI_MAP_DISC_ACCENT), "visible destination is pinned on the map");
        assertTrue(containsFont(visibleBuilt, "nordtal:map_8"), "terrain map font is drawn");

        final RideDialog.Context hiddenCtx =
                new RideDialog.Context(graph, origin, stations, Set.of(destId), stops, "Spawn");
        final DialogCanvas hiddenCanvas = RideDialog.buildMapCanvas(
                rideDocument(),
                hiddenCtx,
                new RideDialog.State(false, 0, destId, new RideMapView.Viewport(RideMapView.SCALE_CLOSE, 0, 0)),
                fakeSend(),
                NOOP);
        final Component hiddenBuilt = hiddenCanvas.build(FocusRing.Ground.BEVEL_FOOT);
        assertFalse(
                containsGlyph(hiddenBuilt, Glyphs.UI_MAP_DISC_ACCENT),
                "a hidden destination picked from the list must stay unpinned");
    }

    @Test
    void itineraryOverflowShowsOmittedCountAndNonSmallDestinationUsesVanillaFallback() {
        final List<RailPos> nodes = new ArrayList<>();
        final List<StationRow> stations = new ArrayList<>();
        final List<RailEdge> edges = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            final RailPos pos = new RailPos(400 + i * 80, 64, 400);
            nodes.add(pos);
            final String name = i == 8 ? "Station ★" : "Stop " + i;
            stations.add(new StationRow(
                    new UUID(0, i + 1L),
                    "world",
                    pos.x(),
                    64,
                    pos.z(),
                    "EAST_WEST",
                    UUID.randomUUID(),
                    name,
                    Instant.EPOCH));
            if (i > 0) {
                edges.add(RailEdge.of(nodes.get(i - 1), pos, List.of()));
            }
        }
        final UUID destId = new UUID(0, 9);
        final RailGraph graph = new RailGraph(nodes, List.of(), edges);
        final List<RideList.Stop> stops = List.of(new RideList.Stop(destId, "Station ★", 640, false));
        final RideDialog.Context ctx =
                new RideDialog.Context(graph, nodes.getFirst(), stations, Set.of(), stops, "Spawn");

        final Component built = RideDialog.buildMapCanvas(
                        rideDocument(), ctx, RideDialog.State.initial(), fakeSend(), NOOP)
                .build(FocusRing.Ground.BEVEL_FOOT);

        assertTrue(containsGlyph(built, "+2"), "overflowing itinerary must show omitted count");
        assertTrue(containsGlyph(built, "Station ★"), "non-small destination label falls back to vanilla text");
    }

    private static boolean containsGlyph(final Component component, final String glyph) {
        if (component instanceof TextComponent text && text.content().contains(glyph)) {
            return true;
        }
        return component.children().stream().anyMatch(child -> containsGlyph(child, glyph));
    }

    private static boolean containsFont(final Component component, final String fontId) {
        if (component.font() != null && fontId.equals(component.font().asString())) {
            return true;
        }
        return component.children().stream().anyMatch(child -> containsFont(child, fontId));
    }
}
