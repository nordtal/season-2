package eu.nordtal.season.smp.rail;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Slot;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.ui.BodyColumn;
import eu.nordtal.season.packrendering.ui.BodyLine;
import eu.nordtal.season.packrendering.ui.Colours;
import eu.nordtal.season.packrendering.ui.DialogCanvas;
import eu.nordtal.season.packrendering.ui.DialogPanel;
import eu.nordtal.season.packrendering.ui.DialogTitle;
import eu.nordtal.season.packrendering.ui.FocusRing;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Look;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.packrendering.ui.Tooltip;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.dialog.Paging;
import io.papermc.paper.dialog.Dialog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The ride dialog for a rider in a minecart: a terrain map, route itinerary and toggleable station list. */
final class RideDialog {

    record Stop(
            @Example("Harbour") String name,
            @Example("8925") long distance) {}

    /** Interactive view state preserved across pan, zoom, selection and list-toggle redraws. */
    record State(boolean listMode, int page, @Nullable UUID selected, RideMapView.@Nullable Viewport viewport) {

        static State initial() {
            return new State(false, 0, null, null);
        }

        State withListMode(final boolean nextList, final int nextPage) {
            return new State(nextList, nextPage, selected, viewport);
        }

        State withSelection(final UUID nextSelected, final RideMapView.@Nullable Viewport nextViewport) {
            return new State(false, page, nextSelected, nextViewport);
        }

        State withViewport(final RideMapView.Viewport nextViewport) {
            return new State(false, page, selected, nextViewport);
        }
    }

    /** Complete snapshot of the network and rider position used to render one ride dialog frame. */
    record Context(
            RailGraph graph,
            RailPos origin,
            Collection<StationRow> stations,
            Set<UUID> hidden,
            List<RideList.Stop> stops,
            String spawnName) {}

    static final DocumentSpec<DocumentSpec.None> DOCUMENT = DocumentSpec.menu(
                    "smp",
                    "ride",
                    "Menus",
                    DocumentSpec.None.class,
                    1,
                    Slot.label("distance", Stop.class),
                    Slot.label("add"),
                    Slot.label("remove"),
                    Slot.label("favourite"),
                    Slot.label("none"),
                    Slot.label("pager", Paging.Page.class),
                    Slot.label("ride"),
                    Slot.label("origin-track"),
                    Slot.label("pan-left"),
                    Slot.label("pan-right"),
                    Slot.label("pan-up"),
                    Slot.label("pan-down"),
                    Slot.label("zoom-in"),
                    Slot.label("zoom-out"),
                    Slot.label("list"),
                    Slot.label("map"),
                    Slot.label("select-hint"))
            .titled("Where to");

    /** What the rider's clicks do: ride to the selected stop, star it, select a stop or update pan/zoom/list state. */
    interface Answers {

        void ride(Player player, UUID station, State state);

        void star(Player player, UUID station, State state);

        void select(Player player, UUID station, State state);

        void update(Player player, State state);
    }

    static final int EACH = 5;

    /** A list row holds any 32-character name and a five-digit distance on the 280 px panel. */
    static final int ROW = 246;

    static final int WIDTH = ROW + BodyLine.SIDE_GAP + BodyLine.PLATE;

    /** Map frame geometry in content coordinates on {@link DialogPanel#WIDE_161}. */
    static final int MAP_X = 0;

    static final int MAP_Y = 1;
    static final int MAP_OUTER_W = 178;
    static final int MAP_OUTER_H = 112;
    static final int MAP_INNER_X = 1;
    static final int MAP_INNER_Y = 2;
    static final int CONTROLS_Y = 119;
    static final int LIST_PLATE_W = 50;

    /** Itinerary column geometry right of the map (7 px gap after the 178 px map). */
    static final int ITIN_X = 185;

    static final int ITIN_LINE_X = 187;
    static final int ITIN_TEXT_X = 195;
    static final int ITIN_TEXT_W = WIDTH - ITIN_TEXT_X;
    static final int RIDE_PLATE_W = WIDTH - ITIN_X - BodyLine.PLATE - BodyLine.SIDE_GAP;

    private static final int ORIGIN_Y = 2;
    private static final int LINE_TOP_Y = 11;
    private static final int LINE_BOTTOM_Y = 99;
    private static final int DEST_DIST_Y = 99;
    private static final int DEST_PIN_Y = 102;
    private static final int DEST_NAME_Y = 106;
    private static final int[] ITIN_SLOTS = {16, 30, 44, 58, 72, 86};

    private static final int PAGER_Y = EACH * BodyLine.PLATE + (EACH - 1) * BodyLine.SIDE_GAP + 7;
    private static final int MAP_BACK_W = 56;
    private static final int TEXT_TOP = 50;
    private static final int EMPTY_TEXT = BodyColumn.BODY_TOP + 5;

    private sealed interface ItinEntry permits SwitchEntry, StopEntry, MoreEntry {}

    private record SwitchEntry(int count) implements ItinEntry {}

    private record StopEntry(String name) implements ItinEntry {}

    private record MoreEntry(int omitted) implements ItinEntry {}

    private RideDialog() {}

    /** Builds the ride dialog (empty state, map view or station list) for {@code context} and {@code state}. */
    static Dialog of(
            final GameDocument document,
            final Context context,
            final State state,
            final Dialogs.Send send,
            final Answers answers) {
        if (context.stops().isEmpty()) {
            return emptyDialog(document);
        }
        if (state.listMode()) {
            return listDialog(document, context, state, send, answers);
        }
        return mapDialog(document, context, state, send, answers);
    }

    private static Dialog emptyDialog(final GameDocument document) {
        final DialogTitle title = DialogTitle.of(DialogPanel.RIDE_LIST_33, TEXT_TOP)
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of());
        title.centred(EMPTY_TEXT, document.label("none"), Colours.SOFT);
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build()).body(List.of()).build())
                .type(Dialogs.bodyOnly()));
    }

    private static Dialog mapDialog(
            final GameDocument document,
            final Context context,
            final State state,
            final Dialogs.Send send,
            final Answers answers) {
        final DialogCanvas canvas = buildMapCanvas(document, context, state, send, answers);
        final DialogTitle title = DialogTitle.of(DialogPanel.WIDE_161, canvas.panelTop())
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of());
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build())
                        .body(List.of(Dialogs.line(canvas, FocusRing.Ground.BEVEL_FOOT, title)))
                        .build())
                .type(Dialogs.bodyOnly()));
    }

    /** Builds the ride map canvas on {@link DialogPanel#WIDE_161} for {@code context} and {@code state}. */
    static DialogCanvas buildMapCanvas(
            final GameDocument document,
            final Context context,
            final State state,
            final Dialogs.Send send,
            final Answers answers) {
        final RideList.Stop selected = resolveSelection(context.stops(), state.selected());
        final Map<UUID, StationRow> byId = new HashMap<>();
        context.stations().forEach(row -> byId.put(row.id(), row));
        final @Nullable StationRow selectedRow = byId.get(selected.station());
        final boolean selectedHidden = context.hidden().contains(selected.station());
        final RideMapView.Viewport viewport = state.viewport() != null
                ? state.viewport()
                : RideMapView.initial(context.origin(), selectedRow == null ? null : selectedRow.pos(), selectedHidden);
        final State resolved = new State(false, state.page(), selected.station(), viewport);
        final Optional<RideItinerary.Plan> plan = RideItinerary.of(
                context.graph(),
                context.origin(),
                context.stations(),
                context.hidden(),
                selected.station(),
                context.spawnName(),
                document.label("origin-track"));

        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.WIDE_161);
        drawTerrainAndFrame(canvas, viewport);
        drawRails(
                canvas,
                context.graph(),
                plan.map(RideItinerary.Plan::routeRails).orElse(List.of()),
                viewport);
        drawMapMarkers(canvas, document, context, byId, selected, selectedHidden, resolved, viewport, send, answers);
        drawMapControls(canvas, document, context.stops().size(), resolved, viewport, send, answers);
        drawItinerary(canvas, document, selected, plan.orElse(null), resolved, send, answers);
        return canvas;
    }

    private static RideList.Stop resolveSelection(final List<RideList.Stop> stops, final @Nullable UUID wanted) {
        if (wanted != null) {
            for (final RideList.Stop stop : stops) {
                if (stop.station().equals(wanted)) {
                    return stop;
                }
            }
        }
        return stops.getFirst();
    }

    private static void drawTerrainAndFrame(final DialogCanvas canvas, final RideMapView.Viewport view) {
        canvas.place(MAP_X, MAP_Y, true, line -> line.art(Glyphs.UI_MAP_BG, MAP_OUTER_W));
        final String font = view.mapFont();
        for (int row = 0; row < 2; row++) {
            final int ty = view.tileY() + row;
            if (ty >= view.rows()) {
                continue;
            }
            final boolean secondRow = row == 1;
            for (int col = 0; col < 3; col++) {
                final int tx = view.tileX() + col;
                if (tx >= view.columns()) {
                    continue;
                }
                final String glyph = view.tileGlyph(tx, ty, secondRow);
                final int x = MAP_INNER_X + col * RideMapView.TILE_PX;
                canvas.place(x, MAP_INNER_Y, true, line -> line.externalTile(font, glyph, RideMapView.TILE_PX));
            }
        }
        canvas.place(MAP_OUTER_W, MAP_INNER_Y, true, line -> line.art(Glyphs.UI_MAP_MASK_RIGHT, 80));
        canvas.place(MAP_X, MAP_Y + MAP_OUTER_H, true, line -> line.art(Glyphs.UI_MAP_MASK_BOTTOM, MAP_OUTER_W));
        canvas.place(MAP_X, MAP_Y, true, line -> line.art(Glyphs.UI_MAP_BORDER, MAP_OUTER_W));
    }

    private static void drawRails(
            final DialogCanvas canvas,
            final RailGraph graph,
            final List<RailPos> routeRails,
            final RideMapView.Viewport view) {
        final Map<Integer, List<RideMapView.Run>> byRow = new TreeMap<>();
        for (final RideMapView.Run run : RideMapView.rasterize(graph, routeRails, view)) {
            byRow.computeIfAbsent(run.y(), ignored -> new ArrayList<>()).add(run);
        }
        for (final Map.Entry<Integer, List<RideMapView.Run>> entry : byRow.entrySet()) {
            final List<RideMapView.Run> rowRuns = entry.getValue();
            canvas.place(MAP_INNER_X, MAP_INNER_Y + entry.getKey(), true, line -> {
                for (final RideMapView.Run run : rowRuns) {
                    line.at(run.x()).mapRuns(run.width(), layerColour(run.layer()));
                }
            });
        }
    }

    private static TextColor layerColour(final RideMapView.Layer layer) {
        return switch (layer) {
            case RAIL_EDGE -> Colours.MAP_RAIL_EDGE;
            case RAIL -> Colours.MAP_RAIL;
            case ROUTE_EDGE -> Colours.MAP_ROUTE_EDGE;
            case ROUTE -> Colours.ACCENT;
        };
    }

    private static void drawMapMarkers(
            final DialogCanvas canvas,
            final GameDocument document,
            final Context context,
            final Map<UUID, StationRow> byId,
            final RideList.Stop selected,
            final boolean selectedHidden,
            final State state,
            final RideMapView.Viewport view,
            final Dialogs.Send send,
            final Answers answers) {
        final String selectHint = document.label("select-hint");
        for (final RideList.Stop stop : context.stops()) {
            if (context.hidden().contains(stop.station()) || stop.station().equals(selected.station())) {
                continue;
            }
            final @Nullable StationRow row = byId.get(stop.station());
            if (row == null) {
                continue;
            }
            final int px = view.pixelX(row.x());
            final int py = view.pixelY(row.z());
            if (!view.visible(px, py)) {
                continue;
            }
            final int dx = Math.clamp(px - 2, 0, RideMapView.VIEW_WIDTH - 5);
            final int dy = Math.clamp(py - 2, 0, RideMapView.VIEW_HEIGHT - 5);
            final String dist = document.label("distance", new Stop(stop.name(), stop.distance()));
            final BodyLine.Press press = new BodyLine.Press(
                    send.click(player -> answers.select(player, stop.station(), state)),
                    Tooltip.hover(stop.name(), dist, selectHint));
            canvas.place(
                    MAP_INNER_X + dx,
                    MAP_INNER_Y + dy,
                    true,
                    line -> line.button(press, part -> part.mapArt(Glyphs.UI_MAP_DISC, 5)));
        }
        drawCartBadge(canvas, context.origin(), view);
        if (!selectedHidden) {
            final @Nullable StationRow destRow = byId.get(selected.station());
            if (destRow != null) {
                drawDestinationPinAndLabel(canvas, document, selected, destRow, view);
            }
        }
    }

    private static void drawCartBadge(
            final DialogCanvas canvas, final RailPos origin, final RideMapView.Viewport view) {
        final int ox = view.pixelX(origin.x());
        final int oy = view.pixelY(origin.z());
        if (!view.visible(ox, oy)) {
            return;
        }
        final int dx = Math.clamp(ox - 4, 0, RideMapView.VIEW_WIDTH - 9);
        final int dy = Math.clamp(oy - 4, 0, RideMapView.VIEW_HEIGHT - 9);
        canvas.place(MAP_INNER_X + dx, MAP_INNER_Y + dy, true, line -> line.mapArt(Glyphs.UI_MAP_CART_BADGE, 9));
    }

    private static void drawDestinationPinAndLabel(
            final DialogCanvas canvas,
            final GameDocument document,
            final RideList.Stop selected,
            final StationRow row,
            final RideMapView.Viewport view) {
        final int px = view.pixelX(row.x());
        final int py = view.pixelY(row.z());
        if (!view.visible(px, py)) {
            return;
        }
        final int dx = Math.clamp(px - 3, 0, RideMapView.VIEW_WIDTH - 7);
        final int dy = Math.clamp(py - 3, 0, RideMapView.VIEW_HEIGHT - 7);
        final String dist = document.label("distance", new Stop(selected.name(), selected.distance()));
        final BodyLine.Press hover = new BodyLine.Press(null, Tooltip.hover(selected.name(), dist, null));
        canvas.place(
                MAP_INNER_X + dx,
                MAP_INNER_Y + dy,
                true,
                line -> line.button(hover, part -> part.mapArt(Glyphs.UI_MAP_DISC_ACCENT, 7)));

        final RideMapView.LabelBox box = RideMapView.destinationLabel(selected.name(), px, py);
        final int pillY = box.small()
                ? MAP_INNER_Y + box.y()
                : Math.clamp(nearestTextY(MAP_INNER_Y + box.y() + 2) - 2, MAP_INNER_Y + 2, MAP_INNER_Y + 97);
        for (int r = 0; r < box.height(); r++) {
            final int rowY = pillY + r;
            canvas.place(MAP_INNER_X + box.x(), rowY, true, line -> line.mapRuns(box.width(), Colours.ACCENT));
        }
        if (box.small()) {
            canvas.small(MAP_INNER_X + box.x() + 3, pillY + 1, box.text(), Colours.ON_ACCENT);
        } else {
            canvas.text(MAP_INNER_X + box.x() + 3, pillY + 2, box.text(), Colours.ON_ACCENT);
        }
    }

    private static int nearestTextY(final int desiredY) {
        final int shiftBase = DialogCanvas.of(DialogPanel.WIDE_161).top() - 4;
        final int wantedShift = shiftBase + desiredY;
        int bestShift = wantedShift;
        int bestDist = Integer.MAX_VALUE;
        for (final int available : Glyphs.FONT_UI_TEXT.keySet()) {
            final int dist = Math.abs(available - wantedShift);
            if (dist < bestDist) {
                bestDist = dist;
                bestShift = available;
            }
        }
        return bestShift - shiftBase;
    }

    private static void drawMapControls(
            final DialogCanvas canvas,
            final GameDocument document,
            final int stopCount,
            final State state,
            final RideMapView.Viewport view,
            final Dialogs.Send send,
            final Answers answers) {
        controlPlate(
                canvas,
                0,
                Symbol.LEFT,
                view.canPanLeft(),
                document.label("pan-left"),
                send,
                player -> answers.update(player, state.withViewport(view.pan(-1, 0))));
        controlPlate(
                canvas,
                20,
                Symbol.UP,
                view.canPanUp(),
                document.label("pan-up"),
                send,
                player -> answers.update(player, state.withViewport(view.pan(0, -1))));
        controlPlate(
                canvas,
                40,
                Symbol.DOWN,
                view.canPanDown(),
                document.label("pan-down"),
                send,
                player -> answers.update(player, state.withViewport(view.pan(0, 1))));
        controlPlate(
                canvas,
                60,
                Symbol.RIGHT,
                view.canPanRight(),
                document.label("pan-right"),
                send,
                player -> answers.update(player, state.withViewport(view.pan(1, 0))));
        controlPlate(
                canvas,
                84,
                Symbol.PLUS,
                view.canZoomIn(),
                document.label("zoom-in"),
                send,
                player -> answers.update(player, state.withViewport(view.zoomed(RideMapView.SCALE_CLOSE))));
        controlPlate(
                canvas,
                104,
                Symbol.MINUS,
                view.canZoomOut(),
                document.label("zoom-out"),
                send,
                player -> answers.update(player, state.withViewport(view.zoomed(RideMapView.SCALE_FAR))));

        final BodyLine.Press listPress = new BodyLine.Press(
                send.click(player -> answers.update(player, state.withListMode(true, 0))),
                Tooltip.hover(document.label("list"), null, null));
        canvas.plate(128, CONTROLS_Y, LIST_PLATE_W, Symbol.LIST, Integer.toString(stopCount), Look.NORMAL, listPress);
    }

    private static void controlPlate(
            final DialogCanvas canvas,
            final int x,
            final Symbol symbol,
            final boolean enabled,
            final String label,
            final Dialogs.Send send,
            final Consumer<Player> action) {
        final Look look = enabled ? Look.NORMAL : Look.DISABLED;
        final BodyLine.Press press = enabled
                ? new BodyLine.Press(send.click(action), Tooltip.hover(label, null, null))
                : BodyLine.Press.NONE;
        canvas.symbolPlate(x, CONTROLS_Y, symbol, look.ink(), look, press, label);
    }

    private static void drawItinerary(
            final DialogCanvas canvas,
            final GameDocument document,
            final RideList.Stop selected,
            final RideItinerary.@Nullable Plan plan,
            final State state,
            final Dialogs.Send send,
            final Answers answers) {
        final String originName = plan != null ? plan.originName() : document.label("origin-track");
        canvas.symbol(ITIN_X, ORIGIN_Y, Symbol.CART, Colours.INK);
        canvas.text(ITIN_TEXT_X, ORIGIN_Y, Letters.cut(originName, ITIN_TEXT_W), Colours.INK);

        for (int y = LINE_TOP_Y; y <= LINE_BOTTOM_Y; y++) {
            canvas.place(ITIN_LINE_X, y, true, line -> line.mapRuns(3, Colours.ACCENT));
        }

        if (plan != null) {
            drawItineraryEntries(canvas, buildEntries(plan));
        }

        final int distance = plan != null ? plan.distance() : selected.distance();
        canvas.symbol(ITIN_X, DEST_PIN_Y, Symbol.PIN, Colours.ACCENT);
        canvas.small(ITIN_TEXT_X, DEST_DIST_Y, RideItinerary.smallMetres(distance), Colours.SOFT);
        canvas.text(ITIN_TEXT_X, DEST_NAME_Y, Letters.cut(selected.name(), ITIN_TEXT_W), Colours.INK);

        final BodyLine.Press starPress = new BodyLine.Press(
                send.click(player -> answers.star(player, selected.station(), state)),
                Tooltip.hover(document.label(selected.favourite() ? "remove" : "add"), null, null));
        canvas.star(
                ITIN_X,
                CONTROLS_Y,
                selected.favourite(),
                selected.favourite() ? document.label("favourite") : null,
                starPress);

        final BodyLine.Press ridePress =
                new BodyLine.Press(send.click(player -> answers.ride(player, selected.station(), state)), null);
        canvas.plate(
                ITIN_X + BodyLine.PLATE + BodyLine.SIDE_GAP,
                CONTROLS_Y,
                RIDE_PLATE_W,
                null,
                document.label("ride"),
                Look.PRIMARY,
                ridePress);
    }

    private static List<ItinEntry> buildEntries(final RideItinerary.Plan plan) {
        final List<ItinEntry> entries = new ArrayList<>();
        for (final RideItinerary.Passed passed : plan.passed()) {
            if (passed.switchesBefore() > 0) {
                entries.add(new SwitchEntry(passed.switchesBefore()));
            }
            entries.add(new StopEntry(passed.name()));
        }
        if (plan.switchesBeforeDestination() > 0) {
            entries.add(new SwitchEntry(plan.switchesBeforeDestination()));
        }
        if (entries.size() <= ITIN_SLOTS.length) {
            return entries;
        }
        final List<ItinEntry> fitted = new ArrayList<>(ITIN_SLOTS.length);
        fitted.addAll(entries.subList(0, ITIN_SLOTS.length - 2));
        final int omitted = entries.size() - (ITIN_SLOTS.length - 1);
        fitted.add(new MoreEntry(omitted));
        fitted.add(entries.getLast());
        return fitted;
    }

    private static void drawItineraryEntries(final DialogCanvas canvas, final List<ItinEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        final int[] slots = pickSlots(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            final int y = slots[i];
            final ItinEntry entry = entries.get(i);
            if (entry instanceof SwitchEntry sw) {
                canvas.place(ITIN_TEXT_X, y + 1, true, line -> line.mapArt(Glyphs.UI_MAP_SWITCH, 7, Colours.SOFT));
                canvas.small(ITIN_TEXT_X + 9, y, Integer.toString(sw.count()), Colours.SOFT);
            } else if (entry instanceof MoreEntry more) {
                canvas.small(ITIN_TEXT_X, y, "+" + more.omitted(), Colours.SOFT);
            } else if (entry instanceof StopEntry stop) {
                canvas.place(ITIN_LINE_X - 1, y + 1, true, line -> line.mapArt(Glyphs.UI_MAP_DISC, 5));
                if (RideItinerary.fitsSmallFont(stop.name())) {
                    canvas.small(ITIN_TEXT_X, y, MenuFont.fit(stop.name(), ITIN_TEXT_W), Colours.INK);
                } else {
                    canvas.text(ITIN_TEXT_X, y, Letters.cut(stop.name(), ITIN_TEXT_W), Colours.INK);
                }
            }
        }
    }

    private static int[] pickSlots(final int count) {
        return switch (count) {
            case 1 -> new int[] {ITIN_SLOTS[2]};
            case 2 -> new int[] {ITIN_SLOTS[1], ITIN_SLOTS[4]};
            case 3 -> new int[] {ITIN_SLOTS[1], ITIN_SLOTS[3], ITIN_SLOTS[5]};
            case 4 -> new int[] {ITIN_SLOTS[0], ITIN_SLOTS[2], ITIN_SLOTS[3], ITIN_SLOTS[5]};
            case 5 -> new int[] {ITIN_SLOTS[0], ITIN_SLOTS[1], ITIN_SLOTS[2], ITIN_SLOTS[4], ITIN_SLOTS[5]};
            default -> ITIN_SLOTS;
        };
    }

    private static Dialog listDialog(
            final GameDocument document,
            final Context context,
            final State state,
            final Dialogs.Send send,
            final Answers answers) {
        final List<RideList.Stop> stops = context.stops();
        final Paging paging = Paging.of(stops.size(), EACH);
        final int page = Math.clamp(state.page(), 0, Math.max(0, paging.pages() - 1));
        final List<RideList.Stop> shown = paging.slice(stops, page);
        final DialogCanvas canvas = DialogCanvas.of(DialogPanel.RIDE_LIST_146);
        for (int index = 0; index < shown.size(); index++) {
            final RideList.Stop stop = shown.get(index);
            canvas.place(0, index * BodyColumn.STEP, true, line -> listRow(line, document, stop, state, send, answers));
        }
        final BodyLine.Press mapPress =
                new BodyLine.Press(send.click(player -> answers.update(player, state.withListMode(false, page))), null);
        canvas.plate(0, PAGER_Y, MAP_BACK_W, Symbol.MAP, document.label("map"), Look.NORMAL, mapPress);
        if (paging.paged()) {
            final String words = document.label("pager", paging.page(page));
            final int pagerX = (WIDTH - Dialogs.pagerWidth(paging, page)) / 2;
            canvas.place(
                    pagerX,
                    PAGER_Y,
                    true,
                    line -> send.pager(
                            line,
                            paging,
                            page,
                            words,
                            (player, nextPage) -> answers.update(player, state.withListMode(true, nextPage))));
        }
        final DialogTitle title = DialogTitle.of(DialogPanel.RIDE_LIST_146, canvas.panelTop())
                .header(Symbol.RAIL, Dialogs.plain(document.title()), List.of());
        return Dialog.create(builder -> builder.empty()
                .base(Dialogs.base(title.build())
                        .body(List.of(Dialogs.line(canvas, FocusRing.Ground.BEVEL_FOOT, title)))
                        .build())
                .type(Dialogs.bodyOnly()));
    }

    private static void listRow(
            final BodyLine line,
            final GameDocument document,
            final RideList.Stop stop,
            final State state,
            final Dialogs.Send send,
            final Answers answers) {
        final String distance = document.label("distance", new Stop(stop.name(), stop.distance()));
        final BodyLine.Press pick =
                new BodyLine.Press(send.click(player -> answers.select(player, stop.station(), state)), null);
        final BodyLine.Press star = new BodyLine.Press(
                send.click(player -> answers.star(player, stop.station(), state)),
                Tooltip.hover(document.label(stop.favourite() ? "remove" : "add"), null, null));
        line.button(pick, part -> part.row(ROW, null, null, stop.name(), distance, Look.NORMAL))
                .gap(BodyLine.SIDE_GAP)
                .button(star, part -> {
                    part.star(stop.favourite());
                    if (stop.favourite()) {
                        part.words(document.label("favourite"));
                    }
                });
    }
}
