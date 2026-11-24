package eu.nordtal.season.packrendering;

import net.kyori.adventure.text.Component;

/** Glyph components rendered on world display entities. */
public final class WorldMarkers {

    private static final Component RIGHT_CLICK = Component.text(Glyphs.WORLD_MOUSE_RIGHT);

    private WorldMarkers() {}

    /** Returns the baseline-centred right-click icon glyph for world display entities. */
    public static Component rightClick() {
        return RIGHT_CLICK;
    }
}
