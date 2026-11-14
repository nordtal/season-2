package eu.nordtal.season.packbuilder.wheel;

/**
 * Where the wheel's parts sit, in GUI pixels of a 480 x 270 screen, each from its body's origin.
 * The column holds {@link #BODIES} item bodies 10 apart, and the client's empty button column 10 below them,
 * so it is 168 high and starts at 63. The rows' texts lie over the prizes at rest, to show their items.
 */
public final class WheelLayout {

    public static final int BODIES = 6;
    public static final int DISC_BODY = 0;
    public static final int PRIZES_BODY = 1;
    public static final int FOIL_BODY = 2;
    public static final int PLATE_BODY = 3;
    public static final int OVERLAY_BODY = 4;
    public static final int HOVER_BODY = 5;

    static final int DISC = 156;
    static final int PLACES = WheelTurn.PLACES;
    private static final double RADIUS = DISC / 2.0 - 20;

    /** The row tops from the column's top, with a 10-pixel gap after each row. */
    private static final int[] ROWS = {0, 11, 47, 74, 105, 132};

    /**
     * The rows' heights; the text of a row of 26 has two lines, of 17 one, centred in its row.
     * Each row is taller than its text by an even number, so the centring needs no rounding.
     */
    public static final int[] HEIGHTS = {1, 26, 17, 21, 17, 26};

    /** The width of every row with a description, from its item's left to its text's right. */
    public static final int ROW_WIDTH = 254;

    /** The item's width in a row with a description, but for the plate's, whose item hovers the prize left of it. */
    public static final int ITEM_WIDTH = 1;

    /** The plate row's item: the hover of the prize at place 9, from 11 left of it to its right edge. */
    public static final int PLATE_ITEM = 27;

    /** The width of the disc's item, which has no text. */
    public static final int LONE_ITEM = 2;

    /** The disc canvas's top-left from the column's top-middle. */
    private static final int[] CANVAS = {-122, 8};

    static final int[] POINTER = {DISC / 2 - 8, -8};

    /** The off plate's top-left on the disc canvas: 13 right of the disc, centred on its height. */
    static final int[] PLATE = {DISC + 13, DISC / 2 - 8};

    static final int PLATE_WIDTH = 75;
    static final int PLATE_HEIGHT = 17;

    /** The rectangle every body spans on the disc canvas: the art with a margin. */
    static final int[] ANCHOR = {-2, -10, DISC + 2, DISC + 10};

    /** The rectangle the overlay spans, which takes in the plate. */
    static final int[] OVERLAY_ANCHOR = {-2, -10, PLATE[0] + PLATE_WIDTH + 2, DISC + 10};

    private WheelLayout() {}

    /** Body {@code body}'s item origin from the column's top-middle. */
    static int[] origin(final int body) {
        final int x = body == DISC_BODY ? -LONE_ITEM / 2 : -ROW_WIDTH / 2;
        return new int[] {x, ROWS[body]};
    }

    /** The disc canvas's top-left from body {@code body}'s origin. */
    static int[] canvas(final int body) {
        final int[] o = origin(body);
        return new int[] {CANVAS[0] - o[0], CANVAS[1] - o[1]};
    }

    /** The upright prize's top-left on the disc canvas at {@code angle} degrees clockwise from the top. */
    public static int[] upright(final double angle) {
        final double a = Math.toRadians(angle);
        return new int[] {
            (int) Math.floor(DISC / 2.0 - 8 + RADIUS * Math.sin(a) + 0.5),
            (int) Math.floor(DISC / 2.0 - 8 - RADIUS * Math.cos(a) + 0.5)
        };
    }

    /** The upright prize at place {@code place} at rest, its top-left from the column's top-middle. */
    public static int[] place(final int place) {
        final int[] p = upright(30.0 * place);
        return new int[] {CANVAS[0] + p[0], CANVAS[1] + p[1]};
    }

    /** The place whose prize the plate row's item hovers: the left one on the wheel's middle height. */
    public static final int ITEM_PLACE = 9;

    /** The row whose text hovers the prize at place {@code place} at rest. */
    public static int hoverBody(final int place) {
        return switch (place) {
            case 11, 0, 1 -> PRIZES_BODY;
            case 10, 2 -> FOIL_BODY;
            case 9, 3 -> PLATE_BODY;
            case 8, 4 -> OVERLAY_BODY;
            default -> HOVER_BODY;
        };
    }

    /** Row {@code body}'s text's left, inside its box's padding of 4, from the column's middle. */
    public static int textLeft(final int body) {
        return -ROW_WIDTH / 2 + (body == PLATE_BODY ? PLATE_ITEM : ITEM_WIDTH) + 2 + 4;
    }

    /** The off plate's left from the plate row's text's left. */
    public static int plateInText() {
        return CANVAS[0] + PLATE[0] - textLeft(PLATE_BODY);
    }
}
