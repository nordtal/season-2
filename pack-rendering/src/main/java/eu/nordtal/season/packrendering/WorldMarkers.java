package eu.nordtal.season.packrendering;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Glyph components rendered on world display entities. */
public final class WorldMarkers {

    private static final Component RIGHT_CLICK = Component.text(Glyphs.WORLD_MOUSE_RIGHT)
            .font(Key.key(Glyphs.FONT_NPC_PLATE))
            .color(NamedTextColor.GREEN);

    private WorldMarkers() {}

    /** Returns the baseline-centred green right-click icon glyph for world display entities. */
    public static Component rightClick() {
        return RIGHT_CLICK;
    }
}
