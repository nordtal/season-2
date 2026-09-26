package eu.nordtal.s2.common.message;

import eu.nordtal.s2.common.Glyphs;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * {@code <glyph:name>}: one of {@link Glyphs#named()}, drawn in {@code minecraft:default}.
 *
 * So a bundle names a glyph instead of carrying its private-use character, which nobody can read
 * or type and which the bundles are held free of. The font is named on the glyph itself: inside a
 * component that sets another font, the same code point draws whatever that font put there.
 *
 * A name nobody knows is left standing as its own text, which is how MiniMessage treats a tag
 * that refuses to resolve - visible in game, rather than silently nothing.
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
