package eu.nordtal.s2.common.access;

/**
 * A play time, in the three units a person thinks in: {@code 1 d 6 h 30 min}.
 *
 * The twin of {@code playtime()} in Steward's {@code format.ts}, deliberately kept short enough
 * that the two cannot drift in any way that matters: same units, same order, same rule that zero
 * parts are left out and that a total of nothing is still {@code 0 min}. Seconds are dropped and
 * never rounded up, so a value read back is the one written. It lives here because the bot writes
 * the {@code SET_PLAYTIME} journal line and admin note, and Steward's dialog asks in the same units -
 * a line saying "111600 seconds" is the one place left where somebody has to divide by 3600.
 */
public final class PlaytimeWording {

    private PlaytimeWording() {}

    public static String of(final long seconds) {
        if (seconds < 0) {
            return "0 min";
        }
        final long minutes = seconds / 60;
        final long days = minutes / (24 * 60);
        final long hours = minutes / 60 % 24;
        final long rest = minutes % 60;
        final StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(" d");
        }
        if (hours > 0) {
            text.append(text.isEmpty() ? "" : " ").append(hours).append(" h");
        }
        if (rest > 0 || text.isEmpty()) {
            text.append(text.isEmpty() ? "" : " ").append(rest).append(" min");
        }
        return text.toString();
    }
}
