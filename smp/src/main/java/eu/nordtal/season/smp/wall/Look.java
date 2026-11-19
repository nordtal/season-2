package eu.nordtal.season.smp.wall;

/**
 * How the wall is drawn: the snow wall the pack's item shader draws, or a pixel-art texture without a shader.
 *
 * Every wall is the snow wall. No setting chooses the pixel look; it is kept for clients that skip the pack's shader.
 */
enum Look {
    FIELD,
    PIXEL;

    /** The pixel look fades its last panels through these models, the outermost first, one block each. */
    private static final String[] PIXEL_FADE = {"border/pixel_fade_1", "border/pixel_fade_2", "border/pixel_fade_3"};

    /**
     * How far from the player panels are sent, in blocks.
     *
     * The field fades by the camera's distance, so it reaches past a third-person camera's fade.
     */
    double reach() {
        return this == FIELD ? Band.RADIUS + Band.CAMERA : Band.RADIUS;
    }

    /**
     * The height of a panel, in blocks, from the band's base up.
     *
     * The field stands 2 blocks taller than the pixel look: that room above the wall holds falling snow only.
     */
    float height() {
        return this == FIELD ? 10 : 8;
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
