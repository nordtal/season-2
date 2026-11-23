package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.packrendering.menu.ItemSprites;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import org.jspecify.annotations.Nullable;

/**
 * An item picture drawn in a dialog: an atlas sprite from {@link ItemSprites}, or a fallback {@link Symbol}.
 * A sprite draws 8 by 8 on the text row and advances 9; a fallback symbol draws 7 by 7 and advances 8.
 */
public record ItemPicture(GameContent.@Nullable Sprite sprite, Symbol fallback, TextColor fallbackColour) {

    /** The cursor advance of an inline atlas sprite: 8 ink columns and 1 spacing column. */
    public static final int SPRITE_ADVANCE = 9;

    /** The small dark field's size in a two-column card. */
    public static final int SMALL_FIELD = 22;

    /** The large dark field's size in a compact choice tile. */
    public static final int LARGE_FIELD = 40;

    public ItemPicture {
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(fallbackColour, "fallbackColour");
    }

    /**
     * Resolves {@code itemKey} (such as {@code minecraft:diamond}) through {@link ItemSprites}.
     * Falls back to {@code fallback} when no atlas sprite exists.
     */
    public static ItemPicture of(
            final @Nullable String itemKey, final Symbol fallback, final TextColor fallbackColour) {
        final GameContent.Sprite sprite =
                itemKey == null ? null : ItemSprites.of(itemKey).orElse(null);
        return new ItemPicture(sprite, fallback, fallbackColour);
    }

    /** Returns an item picture that draws {@code fallback} directly, with no item sprite lookup. */
    public static ItemPicture symbol(final Symbol fallback, final TextColor colour) {
        return new ItemPicture(null, fallback, colour);
    }

    /** Returns whether this picture draws an atlas sprite rather than its fallback symbol. */
    public boolean hasSprite() {
        return sprite != null;
    }

    /** Returns the cursor advance of this picture. */
    public int advance() {
        return sprite != null ? SPRITE_ADVANCE : Symbol.ADVANCE;
    }

    /** Returns the inline sprite component, white and unshadowed so the client draws its true atlas colours. */
    public Component spriteComponent() {
        final GameContent.Sprite current = Objects.requireNonNull(sprite, "no sprite on this picture");
        return Component.object(ObjectContents.sprite(Key.key(current.atlas()), Key.key(current.sprite())))
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .shadowColor(ShadowColor.none());
    }
}
