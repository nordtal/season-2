package eu.nordtal.s2.smp.board;

/**
 * A progress bar made of characters, for the objective board.
 *
 * <p>Not the boss bar's glyph technique: this one hangs in the world as a Text Display, at whatever
 * distance the reader happens to stand, and a bar composed from pixel-width glyph segments is
 * measured in screen pixels that a Text Display does not have. Characters scale with the text they
 * sit in, which is the only thing that is true at every distance.
 *
 * <p>Pure, so the rounding is asserted rather than eyeballed - and the rounding is the whole of it:
 * a bar that shows "full" at 99.6 % is a bar that lies at the only moment anybody is watching it.
 */
public final class ProgressBar {

    private static final char FILLED = '█';
    private static final char EMPTY = '░';

    private ProgressBar() {
    }

    /**
     * @param ratio 0.0 to 1.0; anything outside is clamped rather than refused, because a lowered
     *              target can legitimately leave more collected than is wanted
     * @param width how many characters wide
     */
    public static String of(final double ratio, final int width) {
        if (width <= 0) {
            return "";
        }
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));

        // Floor, not round: only a genuinely complete bar may look complete.
        int filled = (int) Math.floor(clamped * width);
        if (clamped >= 1.0) {
            filled = width;
        } else if (filled >= width) {
            filled = width - 1;
        }
        // And anything that has started must show something, or "1 of 3000" looks like "not begun".
        if (filled == 0 && clamped > 0.0) {
            filled = 1;
        }

        return String.valueOf(FILLED).repeat(filled) + String.valueOf(EMPTY).repeat(width - filled);
    }

    /** The percentage as a whole number, rounded the same way the bar is: down, except at full. */
    public static int percent(final double ratio) {
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));
        return clamped >= 1.0 ? 100 : (int) Math.floor(clamped * 100.0);
    }
}
