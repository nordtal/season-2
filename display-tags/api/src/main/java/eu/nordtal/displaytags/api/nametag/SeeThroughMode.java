package eu.nordtal.displaytags.api.nametag;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a name tag does when a block sits between the viewer and the tag.
 *
 * A text display only knows two of these: its {@code see_through} style flag either lets the text
 * through every block at full brightness or hides it behind the first one. Vanilla's own name tag
 * is neither - it draws the name twice, once without a depth test in a faint white
 * ({@code 0x20FFFFFF}, alpha 32) and once normally and opaque on top, so the name stays readable
 * through a wall while an unobstructed one looks untouched. {@link #VANILLA} reproduces that with
 * two displays; see {@code PlayerNameTagImpl}.
 */
public enum SeeThroughMode {
    /**
     * The name disappears behind the first block.
     *
     * Like a text display with {@code see_through: false}. Written as {@code false} in
     * {@code config.yml}.
     */
    NEVER,

    /**
     * The name is drawn through every block at full opacity.
     *
     * Like a text display with {@code see_through: true}. Written as {@code true} in
     * {@code config.yml}.
     */
    ALWAYS,

    /**
     * The name is drawn through blocks, but dimmed - what a vanilla name tag does.
     */
    VANILLA;

    /**
     * Reads the value of the {@code display.see-through} setting.
     *
     * {@code true} and {@code false} are accepted because that is what the setting held before
     * this mode existed, and a configuration written for an older version has to keep working.
     *
     * @param value the configured value, in any case
     * @return the mode, or {@code null} if the value is not one of the three
     */
    public static @Nullable SeeThroughMode parse(final @Nullable String value) {
        if (value == null) {
            return null;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "false", "never", "off" -> NEVER;
            case "true", "always", "on" -> ALWAYS;
            case "vanilla" -> VANILLA;
            default -> null;
        };
    }

    /**
     * @return the value that represents this mode in {@code config.yml}
     */
    public String configValue() {
        return switch (this) {
            case NEVER -> "false";
            case ALWAYS -> "true";
            case VANILLA -> "vanilla";
        };
    }
}
