package eu.nordtal.season.smp.board;

/**
 * A progress bar made of characters, which scale with a Text Display at any distance, unlike pixel glyphs.
 *
 * Pure, so the rounding is asserted: a bar that shows full at 99.6 % lies at the only moment anybody watches.
 */
public final class ProgressBar {

    private static final char FILLED = '█';
    private static final char EMPTY = '░';

    private ProgressBar() {}

    /**
     * Returns the bar.
     *
     * @param ratio 0.0 to 1.0, clamped, since a lowered target can leave more collected than wanted
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
        // Anything started shows something, or "1 of 3000" looks like "not begun".
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
