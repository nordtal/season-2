package eu.nordtal.season.database.online;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code online_player}: a player that the proxy saw connected.
 *
 * @param name the name last seen, a cached observation, never a key
 * @param subject the compose service of the player, null while no backend has them
 */
public record OnlinePlayer(UUID uuid, String name, String subject, Instant updated) {

    public OnlinePlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(updated, "updated");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank for " + uuid);
        }
        if (subject != null && subject.isBlank()) {
            throw new IllegalArgumentException("subject must be a service name or null, was blank");
        }
    }

    /** Returns the service of the player, empty while no backend has them. */
    public Optional<String> on() {
        return Optional.ofNullable(subject);
    }
}
