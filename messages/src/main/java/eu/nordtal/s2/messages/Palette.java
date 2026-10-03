package eu.nordtal.s2.messages;

/**
 * The colour each {@link Tone} is painted with in one process now, as its {@code colours} settings say.
 * Read on every render, so a changed setting reaches the next line; a process that paints nothing uses
 * {@link #DEFAULTS}.
 */
@FunctionalInterface
public interface Palette {

    /** Every tone's own default. */
    Palette DEFAULTS = Tone::hex;

    /** Returns the colour of {@code tone} as {@code #rrggbb}. */
    String hex(Tone tone);
}
