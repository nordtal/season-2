package eu.nordtal.season.packrendering;

import eu.nordtal.season.messagerendering.GlyphNames;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** The {@code <glyph:name>} tag. It draws a glyph of {@link Glyphs#named()} in {@code minecraft:default}. */
public final class GlyphTag implements GlyphNames {

    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    @Override
    public @Nullable Component glyph(final String name) {
        final String glyph = Glyphs.named().get(name);
        return glyph == null ? null : Component.text(glyph).font(DEFAULT_FONT);
    }
}
