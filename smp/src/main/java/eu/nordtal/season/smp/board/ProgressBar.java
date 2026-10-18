package eu.nordtal.season.smp.board;

/** Renders a progress bar of characters, which scale with a Text Display at any distance. */
public final class ProgressBar {

    private static final char FILLED = '█';
    private static final char EMPTY = '░';

    private ProgressBar() {}

    /**
     * Returns the bar.
     *
     * @param ratio 0.0 to 1.0, clamped
     * @param width the length of the bar in characters
     */
    public static String of(final double ratio, final int width) {
        if (width <= 0) {
            return "";
        }
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));

        // The bar is full only when the ratio is complete.
        int filled = (int) Math.floor(clamped * width);
        if (clamped >= 1.0) {
            filled = width;
        } else if (filled >= width) {
            filled = width - 1;
        }
        // A ratio above 0 shows at least one filled character.
        if (filled == 0 && clamped > 0.0) {
            filled = 1;
        }

        return String.valueOf(FILLED).repeat(filled) + String.valueOf(EMPTY).repeat(width - filled);
    }

    /** Returns the percentage as a whole number, rounded down except at full. */
    public static int percent(final double ratio) {
        final double clamped = Math.max(0.0, Math.min(1.0, ratio));
        return clamped >= 1.0 ? 100 : (int) Math.floor(clamped * 100.0);
    }
}
