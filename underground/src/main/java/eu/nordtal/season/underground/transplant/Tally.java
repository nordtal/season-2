package eu.nordtal.season.underground.transplant;

/** What one run changed, as the fields of the lines it prints. */
public final class Tally {

    long chunks;
    long carved;
    long filled;
    long closed;
    long entrances;
    long mouths;
    long minecarts;
    final long[] placed = new long[2];

    private static final String[] NAMES = {
        "chunks", "closed", "filled", "entrances", "caves", "opened", "entries_kept", "entries_taken", "minecarts"
    };

    /** Returns the counts so far, to print later what changed since. */
    long[] counts() {
        return new long[] {chunks, closed, filled, entrances, mouths, carved, placed[0], placed[1], minecarts};
    }

    /** Returns the fields of what changed since the counts were taken. */
    String since(final long[] earlier) {
        final long[] now = counts();
        final StringBuilder fields = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            fields.append(i == 0 ? "" : " ").append(NAMES[i]).append('=').append(now[i] - earlier[i]);
        }
        return fields.toString();
    }

    @Override
    public String toString() {
        return since(new long[NAMES.length]);
    }
}
