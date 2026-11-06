package eu.nordtal.season.messages.value;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** An item, an advancement, an entity or one of the game's own lines, such as a death message.
 * Minecraft shows a translatable component in the client's own language, other targets the English text.
 *
 * @param english the English text, with {@code %s} or {@code %1$s} where an argument stands
 * @param args the values the game's line fills in, each of a kind of its own, never markup
 * @param sprite the picture Minecraft draws before the name, or null for the name alone. Other targets leave it out.
 */
public record GameContent(
        String key, String english, List<?> args, @Nullable Sprite sprite) {

    /**
     * A sprite of a resource pack's atlas.
     *
     * @param atlas the atlas, such as {@code minecraft:items}
     * @param sprite the sprite in it, such as {@code minecraft:item/diamond}
     */
    public record Sprite(String atlas, String sprite) {

        public Sprite {
            Objects.requireNonNull(atlas, "atlas");
            Objects.requireNonNull(sprite, "sprite");
        }
    }

    public GameContent {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(english, "english");
        args = List.copyOf(args);
    }

    public GameContent(final String key, final String english, final List<?> args) {
        this(key, english, args, null);
    }

    public GameContent(final String key, final String english) {
        this(key, english, List.of());
    }

    /** Returns this content with {@code sprite} drawn before its name, or with the name alone for null. */
    public GameContent drawn(final @Nullable Sprite sprite) {
        return new GameContent(key, english, args, sprite);
    }

    /** Returns the content for a translation key, with the English name derived from the key's last segment. */
    public static GameContent of(final String key) {
        final String id = key.substring(key.lastIndexOf('.') + 1);
        final StringBuilder english = new StringBuilder(id.length());
        boolean start = true;
        for (int index = 0; index < id.length(); index++) {
            final char c = id.charAt(index);
            if (c == '_') {
                english.append(' ');
                start = true;
            } else {
                english.append(start ? Character.toUpperCase(c) : c);
                start = false;
            }
        }
        return new GameContent(key, english.toString());
    }
}
