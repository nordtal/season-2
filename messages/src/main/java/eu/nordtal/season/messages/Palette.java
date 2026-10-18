package eu.nordtal.season.messages;

/**
 * The colour of each {@link Tone} in one process, as its {@code colours} settings say.
 * A render reads it every time. A process that paints nothing uses {@link #DEFAULTS}.
 */
@FunctionalInterface
public interface Palette {

    Palette DEFAULTS = Tone::hex;

    /** Returns the colour as {@code #rrggbb}. */
    String hex(Tone tone);
}
