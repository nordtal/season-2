package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.menu.MenuFont;
import eu.nordtal.season.packrendering.ui.Letters;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Computes the ride map's tile viewport, panning, zooming and clipped rail pixel runs.
 * Supports 8 and 16 blocks per pixel over the play area inside the 176 by 110 viewport.
 */
final class RideMapView {

    /** The play area origin and side in blocks, matching {@code MapArea.PLAY}. */
    static final int PLAY_MIN_X = 0;

    static final int PLAY_MIN_Z = 0;
    static final int PLAY_SIZE = 9984;

    /** The two zoom steps in blocks per pixel, matching {@code MapPack.SCALES}. */
    static final int SCALE_CLOSE = 8;

    static final int SCALE_FAR = 16;

    /** Pixels along one side of a map pack glyph, matching {@code MapPack.GLYPH}. */
    static final int TILE_PX = 64;

    /** First codepoint of each map font, matching {@code MapPack.FIRST_CODEPOINT}. */
    static final int FIRST_CODEPOINT = 0xF0000;

    /** Codepoint offset for the second vertical row ({@code ascent = -TILE_PX}), matching {@code MapPack}. */
    static final int SECOND_ROW_OFFSET = 0x1000;

    /** Inner terrain viewport dimensions inside the 178 by 112 dark frame. */
    static final int VIEW_WIDTH = 176;

    static final int VIEW_HEIGHT = 110;

    /** Rasterized layer priority, painted in ascending order. */
    enum Layer {
        RAIL_EDGE((byte) 1),
        RAIL((byte) 2),
        ROUTE_EDGE((byte) 3),
        ROUTE((byte) 4);

        final byte priority;

        Layer(final byte priority) {
            this.priority = priority;
        }

        static Layer ofPriority(final byte priority) {
            return switch (priority) {
                case 1 -> RAIL_EDGE;
                case 2 -> RAIL;
                case 3 -> ROUTE_EDGE;
                case 4 -> ROUTE;
                default -> throw new IllegalArgumentException("unknown layer priority: " + priority);
            };
        }
    }

    /** One contiguous horizontal pixel run inside the viewport, in viewport coordinates (0..175, 0..109). */
    record Run(int x, int y, int width, Layer layer) {}

    /** Placement of the accent destination label pill inside the viewport. */
    record LabelBox(int x, int y, int width, int height, String text, boolean small) {}

    /** One viewport state: zoom scale (8 or 16 blocks/px) and top-left terrain tile indices. */
    record Viewport(int scale, int tileX, int tileY) {

        Viewport {
            if (scale != SCALE_CLOSE && scale != SCALE_FAR) {
                throw new IllegalArgumentException("unsupported map scale: " + scale);
            }
            final int totalPx = PLAY_SIZE / scale;
            final int maxX = Math.max(0, Math.ceilDiv(totalPx - VIEW_WIDTH, TILE_PX));
            final int maxY = Math.max(0, Math.ceilDiv(totalPx - VIEW_HEIGHT, TILE_PX));
            tileX = Math.clamp(tileX, 0, maxX);
            tileY = Math.clamp(tileY, 0, maxY);
        }

        int columns() {
            return Math.ceilDiv(PLAY_SIZE / scale, TILE_PX);
        }

        int rows() {
            return columns();
        }

        int maxTileX() {
            return Math.max(0, Math.ceilDiv(PLAY_SIZE / scale - VIEW_WIDTH, TILE_PX));
        }

        int maxTileY() {
            return Math.max(0, Math.ceilDiv(PLAY_SIZE / scale - VIEW_HEIGHT, TILE_PX));
        }

        boolean canPanLeft() {
            return tileX > 0;
        }

        boolean canPanRight() {
            return tileX < maxTileX();
        }

        boolean canPanUp() {
            return tileY > 0;
        }

        boolean canPanDown() {
            return tileY < maxTileY();
        }

        boolean canZoomIn() {
            return scale == SCALE_FAR;
        }

        boolean canZoomOut() {
            return scale == SCALE_CLOSE;
        }

        Viewport pan(final int dx, final int dy) {
            return new Viewport(scale, tileX + dx, tileY + dy);
        }

        /** Switches to {@code nextScale}, keeping the current viewport centre inside the new view. */
        Viewport zoomed(final int nextScale) {
            if (nextScale == scale) {
                return this;
            }
            final int centreBlockX = PLAY_MIN_X + (tileX * TILE_PX + VIEW_WIDTH / 2) * scale;
            final int centreBlockZ = PLAY_MIN_Z + (tileY * TILE_PX + VIEW_HEIGHT / 2) * scale;
            return centredOn(nextScale, centreBlockX, centreBlockZ);
        }

        int pixelX(final int blockX) {
            return Math.floorDiv(blockX - PLAY_MIN_X, scale) - tileX * TILE_PX;
        }

        int pixelY(final int blockZ) {
            return Math.floorDiv(blockZ - PLAY_MIN_Z, scale) - tileY * TILE_PX;
        }

        boolean visible(final int px, final int py) {
            return px >= 0 && px < VIEW_WIDTH && py >= 0 && py < VIEW_HEIGHT;
        }

        /** Returns the glyph for tile ({@code tx}, {@code ty}); {@code secondRow} selects the lowered row. */
        String tileGlyph(final int tx, final int ty, final boolean secondRow) {
            final int base = FIRST_CODEPOINT + ty * columns() + tx;
            return Character.toString(secondRow ? base + SECOND_ROW_OFFSET : base);
        }

        String mapFont() {
            return "nordtal:map_" + scale;
        }
    }

    private RideMapView() {}

    /** Picks the initial viewport framing {@code origin} and {@code destination}, preferring 8 blocks/px. */
    static Viewport initial(
            final RailPos origin, final @Nullable RailPos destination, final boolean destinationHidden) {
        if (destination == null || destinationHidden) {
            return centredOn(SCALE_CLOSE, origin.x(), origin.z());
        }
        final int midX = (origin.x() + destination.x()) / 2;
        final int midZ = (origin.z() + destination.z()) / 2;
        final Viewport close = centredOn(SCALE_CLOSE, midX, midZ);
        if (close.visible(close.pixelX(origin.x()), close.pixelY(origin.z()))
                && close.visible(close.pixelX(destination.x()), close.pixelY(destination.z()))) {
            return close;
        }
        return centredOn(SCALE_FAR, midX, midZ);
    }

    /** Returns a viewport at {@code scale} framing ({@code blockX}, {@code blockZ}) near its centre. */
    static Viewport centredOn(final int scale, final int blockX, final int blockZ) {
        final int worldPx = Math.floorDiv(blockX - PLAY_MIN_X, scale);
        final int worldPy = Math.floorDiv(blockZ - PLAY_MIN_Z, scale);
        final int tx = Math.floorDiv(worldPx - VIEW_WIDTH / 2 + TILE_PX / 2, TILE_PX);
        final int ty = Math.floorDiv(worldPy - VIEW_HEIGHT / 2 + TILE_PX / 2, TILE_PX);
        return new Viewport(scale, tx, ty);
    }

    /** Rasterizes all edges of {@code graph} and {@code routeRails} into merged horizontal runs inside {@code view}. */
    static List<Run> rasterize(final RailGraph graph, final List<RailPos> routeRails, final Viewport view) {
        final byte[][] grid = new byte[VIEW_HEIGHT][VIEW_WIDTH];
        for (final RailEdge edge : graph.edges()) {
            plotEdge(grid, edge, view);
        }
        plotRoute(grid, routeRails, view);
        addCasing(grid, Layer.RAIL.priority, Layer.RAIL_EDGE.priority);
        addCasing(grid, Layer.ROUTE.priority, Layer.ROUTE_EDGE.priority);
        return encodeRuns(grid);
    }

    private static void plotEdge(final byte[][] grid, final RailEdge edge, final Viewport view) {
        int prevX = view.pixelX(edge.from().x());
        int prevY = view.pixelY(edge.from().z());
        paintPoint(grid, prevX, prevY, Layer.RAIL.priority);
        for (final RailPos pos : edge.rails()) {
            final int px = view.pixelX(pos.x());
            final int py = view.pixelY(pos.z());
            plotSegment(grid, prevX, prevY, px, py, 1, Layer.RAIL.priority);
            prevX = px;
            prevY = py;
        }
        plotSegment(
                grid,
                prevX,
                prevY,
                view.pixelX(edge.to().x()),
                view.pixelY(edge.to().z()),
                1,
                Layer.RAIL.priority);
    }

    private static void plotRoute(final byte[][] grid, final List<RailPos> routeRails, final Viewport view) {
        if (routeRails.isEmpty()) {
            return;
        }
        int prevX = view.pixelX(routeRails.getFirst().x());
        int prevY = view.pixelY(routeRails.getFirst().z());
        paintSquare(grid, prevX, prevY, 2, Layer.ROUTE.priority);
        for (int i = 1; i < routeRails.size(); i++) {
            final int px = view.pixelX(routeRails.get(i).x());
            final int py = view.pixelY(routeRails.get(i).z());
            plotSegment(grid, prevX, prevY, px, py, 2, Layer.ROUTE.priority);
            prevX = px;
            prevY = py;
        }
    }

    private static void plotSegment(
            final byte[][] grid,
            final int x0,
            final int y0,
            final int x1,
            final int y1,
            final int size,
            final byte value) {
        final int dx = Math.abs(x1 - x0);
        final int dy = Math.abs(y1 - y0);
        final int sx = x0 < x1 ? 1 : -1;
        final int sy = y0 < y1 ? 1 : -1;
        int x = x0;
        int y = y0;
        int err = dx - dy;
        while (true) {
            if (size == 1) {
                paintPoint(grid, x, y, value);
            } else {
                paintSquare(grid, x, y, size, value);
            }
            if (x == x1 && y == y1) {
                break;
            }
            final int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static void paintPoint(final byte[][] grid, final int px, final int py, final byte value) {
        if (px >= 0 && px < VIEW_WIDTH && py >= 0 && py < VIEW_HEIGHT && grid[py][px] < value) {
            grid[py][px] = value;
        }
    }

    private static void paintSquare(final byte[][] grid, final int cx, final int cy, final int size, final byte value) {
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                paintPoint(grid, cx + dx, cy + dy, value);
            }
        }
    }

    private static void addCasing(final byte[][] grid, final byte target, final byte edge) {
        for (int y = 0; y < VIEW_HEIGHT; y++) {
            for (int x = 0; x < VIEW_WIDTH; x++) {
                if (grid[y][x] != target) {
                    continue;
                }
                paintPoint(grid, x - 1, y, edge);
                paintPoint(grid, x + 1, y, edge);
                paintPoint(grid, x, y - 1, edge);
                paintPoint(grid, x, y + 1, edge);
            }
        }
    }

    private static List<Run> encodeRuns(final byte[][] grid) {
        final List<Run> runs = new ArrayList<>();
        for (int y = 0; y < VIEW_HEIGHT; y++) {
            final byte[] row = grid[y];
            int x = 0;
            while (x < VIEW_WIDTH) {
                final byte cell = row[x];
                if (cell == 0) {
                    x++;
                    continue;
                }
                int end = x + 1;
                while (end < VIEW_WIDTH && row[end] == cell) {
                    end++;
                }
                runs.add(new Run(x, y, end - x, Layer.ofPriority(cell)));
                x = end;
            }
        }
        return runs;
    }

    /** Places the destination's accent label pill near ({@code px}, {@code py}), clamped inside the viewport. */
    static LabelBox destinationLabel(final String name, final int px, final int py) {
        final boolean small = RideItinerary.fitsSmallFont(name);
        final int maxTextWidth = 84;
        final String text = small ? MenuFont.fit(name, maxTextWidth) : Letters.cut(name, maxTextWidth);
        final int textWidth = small ? MenuFont.visible(text) : Letters.visibleWidth(text);
        final int width = textWidth + 6;
        final int height = small ? 9 : 11;
        final int x = Math.clamp(px - width / 2, 2, VIEW_WIDTH - width - 2);
        final int aboveY = py - 5 - height;
        final int y = aboveY >= 2 ? aboveY : Math.min(VIEW_HEIGHT - height - 2, py + 6);
        return new LabelBox(x, y, width, height, text, small);
    }
}
