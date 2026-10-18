package eu.nordtal.season.common.id;

import java.util.Objects;
import java.util.UUID;

/** A Minecraft account, by its UUID, never by its name. */
public record PlayerId(UUID value) {

    public PlayerId {
        Objects.requireNonNull(value, "value");
    }

    public static PlayerId of(final UUID value) {
        return new PlayerId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
