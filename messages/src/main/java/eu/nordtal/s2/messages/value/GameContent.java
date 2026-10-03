package eu.nordtal.s2.messages.value;

import java.util.List;
import java.util.Objects;

/**
 * An item, an advancement, an entity or one of the game's own lines, such as a death message.
 * In Minecraft it is a translatable component in the client's own language, elsewhere its English text.
 *
 * @param key     the game's translation key, such as {@code item.minecraft.diamond}
 * @param english the English text, with {@code %s} or {@code %1$s} where an argument stands
 * @param args    the values the game's line fills in, each of a kind of its own, never markup
 */
public record GameContent(String key, String english, List<?> args) {

    public GameContent {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(english, "english");
        args = List.copyOf(args);
    }

    /** Returns content that fills nothing in, such as an item. */
    public GameContent(final String key, final String english) {
        this(key, english, List.of());
    }

    /** Returns the content behind a translation key, its English name derived from the key's last segment. */
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
