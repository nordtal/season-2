package eu.nordtal.season.displaytags;

import org.jspecify.annotations.Nullable;

/**
 * What a name tag does when a block is between the viewer and the tag.
 * A text display is hidden or at full brightness, so {@link #VANILLA} draws two.
 */
public enum SeeThroughMode {
    /** The name disappears behind the first block: the setting {@code false}. */
    NEVER,

    /** The name is drawn through every block at full opacity: the setting {@code true}. */
    ALWAYS,

    /** The name is drawn through blocks and dimmed, as a vanilla name tag is. */
    VANILLA;

    /** Returns the mode of the {@code see-through} value in any case, or {@code null} if it is none of the three. */
    public static @Nullable SeeThroughMode parse(final @Nullable String value) {
        if (value == null) {
            return null;
        }

        for (final SeeThroughMode mode : values()) {
            if (mode.configValue().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return null;
    }

    public String configValue() {
        return switch (this) {
            case NEVER -> "false";
            case ALWAYS -> "true";
            case VANILLA -> "vanilla";
        };
    }
}
