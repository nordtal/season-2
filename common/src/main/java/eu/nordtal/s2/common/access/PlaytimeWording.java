package eu.nordtal.s2.common.access;

/**
 * A play time, in the three units a person thinks in: {@code 1 d 6 h 30 min}.
 *
 * The twin of {@code playtime()} in Steward's {@code format.ts}; seconds are dropped, never rounded up.
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
