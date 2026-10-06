package eu.nordtal.season.displaytags;

import org.jspecify.annotations.Nullable;

/**
 * What a name tag does when a block sits between the viewer and the tag.
 *
 * A text display is either hidden or at full brightness; {@link #VANILLA} draws two to show a faint name instead.
 */
public enum SeeThroughMode {
    /** The name disappears behind the first block; the setting's {@code false}. */
    NEVER,

    /** The name is drawn through every block at full opacity; the setting's {@code true}. */
    ALWAYS,

    /**
     * The name is drawn through blocks, but dimmed, as a vanilla name tag is.
     */
    VANILLA;

    /**
     * Reads the value of the {@code see-through} setting, in any case.
     *
     * @param value the configured value
     * @return the mode, or {@code null} if the value is not one of the three
     */
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

    /** Returns the value of the {@code see-through} setting that stands for this mode. */
    public String configValue() {
        return switch (this) {
            case NEVER -> "false";
            case ALWAYS -> "true";
            case VANILLA -> "vanilla";
        };
    }
}
