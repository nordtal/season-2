package eu.nordtal.season.packbuilder.wheel;

/**
 * Where the wheel's parts sit, in GUI pixels.
 * The dialog's column holds {@link #BODIES} item bodies 11 apart, body 5 the plate's row with a 100 x 1 item at its
 * left. Every body draws over the same rectangle, so each part is placed from its body's own origin.
 */
final class WheelLayout {

    static final int BODIES = 14;
    static final int DISC = 96;
    static final int PLACES = WheelTurn.PLACES;
    private static final double RADIUS = 34.0;
    private static final int PLATE_ROW = 5;

    /** The plate row's 100-pixel item starts this far left of the column's middle, as the client lays it out. */
    private static final int ROW_LEFT = -93;

    /** The disc canvas's top-left from body 0's origin: the wheel stands at the panel's margin of 9. */
    private static final int[] CANVAS = {-90, 26};

    static final int[] POINTER = {40, -8};

    /** The off plate's top-left on the disc canvas. */
    static final int[] PLATE = {108, 33};

    static final int PLATE_WIDTH = 75;
    static final int PLATE_HEIGHT = 17;

    /** The rectangle every body spans on the disc canvas: the art with a margin. */
    static final int[] ANCHOR = {-2, -10, 98, 106};

    /** The rectangle the overlay spans, which takes in the plate. */
    static final int[] OVERLAY_ANCHOR = {-2, -10, 184, 106};

    /** The plate row's height: its description's padding 4 above and below three text lines. */
    private static final int PLATE_ROW_HEIGHT = 4 + 3 * 9 + 4;

    private WheelLayout() {}

    /** Body {@code body}'s item origin from body 0's; the plate row's 1-pixel item sits at its row's middle. */
    static int[] origin(final int body) {
        if (body < PLATE_ROW) {
            return new int[] {0, 11 * body};
        }
        if (body == PLATE_ROW) {
            return new int[] {ROW_LEFT, 11 * PLATE_ROW + (PLATE_ROW_HEIGHT - 1) / 2};
        }
        return new int[] {0, 11 * PLATE_ROW + PLATE_ROW_HEIGHT + 10 + 11 * (body - PLATE_ROW - 1)};
    }

    /** The disc canvas's top-left from body {@code body}'s origin. */
    static int[] canvas(final int body) {
        final int[] o = origin(body);
        return new int[] {CANVAS[0] - o[0], CANVAS[1] - o[1]};
    }

    /** The upright prize's top-left on the disc canvas at {@code angle} degrees clockwise from the top. */
    static int[] upright(final double angle) {
        final double a = Math.toRadians(angle);
        return new int[] {
            (int) Math.floor(40 + RADIUS * Math.sin(a) + 0.5), (int) Math.floor(40 - RADIUS * Math.cos(a) + 0.5)
        };
    }
}
