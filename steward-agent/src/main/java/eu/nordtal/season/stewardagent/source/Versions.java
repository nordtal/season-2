package eu.nordtal.season.stewardagent.source;

import java.util.regex.Pattern;

/** The order of dotted version numbers, for Paper, Velocity and our releases. */
public final class Versions {

    private static final Pattern DOT = Pattern.compile("\\.");

    private Versions() {}

    /**
     * Compares component by component as numbers. A missing component counts as zero.
     *
     * @throws NumberFormatException when a component is not a number
     */
    public static int compare(final String left, final String right) {
        final String[] mine = DOT.splitAsStream(left).toArray(String[]::new);
        final String[] theirs = DOT.splitAsStream(right).toArray(String[]::new);
        for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
            final int a = i < mine.length ? Integer.parseInt(mine[i]) : 0;
            final int b = i < theirs.length ? Integer.parseInt(theirs[i]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }
}
