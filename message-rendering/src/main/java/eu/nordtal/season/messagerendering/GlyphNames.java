package eu.nordtal.season.messagerendering;

import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** Draws a glyph by its name, for the {@code <glyph:name>} tag. A {@link java.util.ServiceLoader} finds it. */
public interface GlyphNames {

    /** Returns the component of the glyph called {@code name}, or {@code null} if there is none. */
    @Nullable
    Component glyph(String name);
}
