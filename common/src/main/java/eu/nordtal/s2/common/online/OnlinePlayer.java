package eu.nordtal.s2.common.online;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code online_player}: a player the proxy saw connected at {@code updated}.
 *
 * @param name    the name last seen on the connection; a cached observation, never a key
 * @param subject the compose service the player was on, or {@code null} while no backend has them yet
 * @param updated when the proxy last saw them connected
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

    /**
     * @return the service this player is on, empty when the proxy has them but no backend does -
     *         which is a player who is online, not one who is anywhere in particular
     */
    public Optional<String> on() {
        return Optional.ofNullable(subject);
    }
}
