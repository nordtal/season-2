package eu.nordtal.season.common.id;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The Discord account of a person, by its snowflake: digits only. */
public record DiscordId(String value) {

    public DiscordId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a Discord id is never blank");
        }
    }

    public static DiscordId of(final String value) {
        return new DiscordId(value);
    }

    public static @Nullable DiscordId ofNullable(final @Nullable String value) {
        return value == null ? null : new DiscordId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
