package eu.nordtal.s2.packrendering;

import eu.nordtal.s2.messagerendering.GlyphNames;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * The pack's side of {@code <glyph:name>}: one of {@link Glyphs#named()}, drawn in {@code minecraft:default}.
 * The font is named on the glyph because another font draws something else at that code point.
 */
public final class GlyphTag implements GlyphNames {

    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    @Override
    public @Nullable Component glyph(final String name) {
        final String glyph = Glyphs.named().get(name);
        return glyph == null ? null : Component.text(glyph).font(DEFAULT_FONT);
    }
}
