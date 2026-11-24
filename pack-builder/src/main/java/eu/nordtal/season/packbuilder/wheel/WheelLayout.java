package eu.nordtal.season.packbuilder.wheel;

import java.util.List;

/**
 * Where the wheel's parts sit, in GUI pixels of a 480 x 270 screen, each from its body's origin.
 * The column holds {@link #BODIES} item bodies 10 apart: a lone base art body first, then the glinting prizes with
 * a 17-line description (161 high) whose focus ring lies only on static pixels.
 */
public final class WheelLayout {

    public static final int BODIES = 2;
    public static final int BASE_BODY = 0;
    public static final int FOIL_BODY = 1;

    static final int DISC = 156;
    static final int PLACES = WheelTurn.PLACES;
    private static final double RADIUS = DISC / 2.0 - 20;

    /** The row tops from the column's top, with a 10-pixel gap after each row. */
    private static final int[] ROWS = {0, 11};

    /** The text lines of the description body: 9 * 17 + 8 = 161 high. */
    public static final int LINES = 17;

    /** The rows' heights: 1 for the lone base art body, 161 for the 17-line description body. */
    public static final List<Integer> HEIGHTS = List.of(1, 9 * LINES + 8);

    /** The width of the description row, from its item's left to its text's right. */
    public static final int ROW_WIDTH = 254;

    /** The item's width in the description row, even so the description text width is even. */
    public static final int ITEM_WIDTH = 2;

    /** The width of the lone base art body, which has no text. */
    public static final int LONE_ITEM = 2;

    /** The disc canvas's top-left from the column's top-middle. */
    private static final int[] CANVAS = {-122, 16};

    static final int[] POINTER = {DISC / 2 - 8, -8};

    /** The off plate's top-left on the disc canvas: 13 right of the disc, centred on its height. */
    static final int[] PLATE = {DISC + 13, DISC / 2 - 8};

    static final int PLATE_WIDTH = 75;
    static final int PLATE_HEIGHT = 17;

    /** The rectangle every body spans on the disc canvas: the art with a margin. */
    static final int[] ANCHOR = {-2, -10, DISC + 2, DISC + 10};

    /** The rectangle the base body spans, which takes in the plate. */
    static final int[] OVERLAY_ANCHOR = {-2, -10, PLATE[0] + PLATE_WIDTH + 2, DISC + 10};

    private WheelLayout() {}

    /** Body {@code body}'s item origin from the column's top-middle. */
    static int[] origin(final int body) {
        final int x = body == BASE_BODY ? -LONE_ITEM / 2 : -ROW_WIDTH / 2;
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

    /** The description text's left, inside its box's padding of 4, from the column's middle. */
    public static int textLeft() {
        return -ROW_WIDTH / 2 + ITEM_WIDTH + 2 + 4;
    }

    /** The description text's top, inside its box's padding of 4, from the column's top. */
    public static int textTop() {
        return ROWS[FOIL_BODY] + 4;
    }

    /** The plate's left from the description text's left. */
    public static int plateX() {
        return CANVAS[0] + PLATE[0] - textLeft();
    }

    /** The plate's top from the description text's top. */
    public static int plateY() {
        return CANVAS[1] + PLATE[1] - textTop();
    }

    /** Place {@code place}'s prize left from the description text's left. */
    public static int hoverX(final int place) {
        return place(place)[0] - textLeft();
    }

    /** Place {@code place}'s prize top from the description text's top. */
    public static int hoverY(final int place) {
        return place(place)[1] - textTop();
    }
}
