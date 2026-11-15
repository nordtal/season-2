package eu.nordtal.season.smp.wall;

import java.util.Locale;

/**
 * How the wall is drawn: a field the pack's item shader draws, or a pixel-art texture without a shader.
 * Players see them named Veil and Lattice.
 */
enum Look {
    FIELD,
    PIXEL;

    /** The pixel look fades its last panels through these models, the outermost first, one block each. */
    private static final String[] PIXEL_FADE = {"border/pixel_fade_1", "border/pixel_fade_2", "border/pixel_fade_3"};

    /** The look a setting names. An unknown name draws the field. */
    static Look named(final String name) {
        return "PIXEL".equals(name.toUpperCase(Locale.ROOT)) ? PIXEL : FIELD;
    }

    /**
     * How far from the player panels are sent, in blocks.
     *
     * The field fades by the camera's distance, so it reaches past a third-person camera's fade.
     */
    double reach() {
        return this == FIELD ? Band.RADIUS + Band.CAMERA : Band.RADIUS;
    }

    /** The item model, in the namespace {@code nordtal}, of a panel this far from the player. */
    String model(final double distance) {
        if (this == FIELD) {
            // The shader fades the field by its distance from the camera.
            return "border/field";
        }
        final int fromEnd = (int) Math.floor(Band.RADIUS - distance);
        return fromEnd >= 0 && fromEnd < PIXEL_FADE.length ? PIXEL_FADE[fromEnd] : "border/pixel";
    }
}
