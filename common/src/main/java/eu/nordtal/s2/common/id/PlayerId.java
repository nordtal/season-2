package eu.nordtal.s2.common.id;

import java.util.Objects;
import java.util.UUID;

/**
 * A Minecraft account, by the UUID Mojang gave it.
 *
 * @param value the account's UUID, never its name
 */
public record PlayerId(UUID value) {

    public PlayerId {
        Objects.requireNonNull(value, "value");
    }

    /** Returns the account {@code value} names. */
    public static PlayerId of(final UUID value) {
        return new PlayerId(value);
    }

    /** Returns the UUID in its usual form, so a log line reads as Mojang writes it. */
    @Override
    public String toString() {
        return value.toString();
    }
}
