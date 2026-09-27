package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.Glyphs;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * {@code <glyph:name>}: one of {@link Glyphs#named()}, drawn in {@code minecraft:default}.
 * The font is named on the glyph because another font draws something else at that code point; an unknown name stays as
 * text.
 */
public final class GlyphTag {

    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    public static final TagResolver RESOLVER = TagResolver.resolver("glyph", (arguments, context) -> {
        final String name = arguments.popOr("a glyph needs a name").value();
        final String glyph = Glyphs.named().get(name);
        if (glyph == null) {
            throw context.newException("no glyph is named " + name, arguments);
        }
        return Tag.selfClosingInserting(Component.text(glyph).font(DEFAULT_FONT));
    });

    private GlyphTag() {}
}
