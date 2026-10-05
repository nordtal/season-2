package eu.nordtal.season.common.id;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A Discord account, by its snowflake: the key of a person in this network.
 *
 * @param value the snowflake as Discord writes it, digits only
 */
public record DiscordId(String value) {

    public DiscordId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("a Discord id is never blank");
        }
    }

    /** Returns the id Discord wrote as {@code value}. */
    public static DiscordId of(final String value) {
        return new DiscordId(value);
    }

    /** Returns the id in {@code value}, or {@code null} for a column or a field that holds none. */
    public static @Nullable DiscordId ofNullable(final @Nullable String value) {
        return value == null ? null : new DiscordId(value);
    }

    /** Returns the snowflake itself, so a log line and a mention read as Discord writes them. */
    @Override
    public String toString() {
        return value;
    }
}
