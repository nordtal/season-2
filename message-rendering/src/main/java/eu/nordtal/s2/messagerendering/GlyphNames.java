package eu.nordtal.s2.messagerendering;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * Draws a glyph by its name, for the {@code <glyph:name>} tag of a bundle text.
 * Found through {@link java.util.ServiceLoader}, so this module needs no resource pack; without one every name is
 * unknown.
 */
public interface GlyphNames {

    /** Returns the component drawing the glyph called {@code name}, or {@code null} if no glyph has that name. */
    @Nullable
    Component glyph(String name);
}
