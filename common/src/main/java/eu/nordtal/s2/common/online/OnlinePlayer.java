package eu.nordtal.s2.common.online;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of {@code online_player}, as read back - a player network-control saw connected at
 * {@code updated} (steward/111).
 *
 * <p>The sibling of {@link OnlineCount} and deliberately not a field on it: that record is one
 * subject's number, this is one person. Neither knows whether it is still true - see
 * {@link OnlineRoster} for why {@code updated} travels with the row rather than being judged where
 * it is written.
 *
 * @param uuid    the Minecraft account, the identifier the rest of the schema already uses
 * @param name    the name last seen on the connection; a cached observation, never a key
 * @param subject the compose service this player was on, or {@code null} for a player the proxy had
 *                and no backend did yet - mid-transfer, or between login and the first server. Not
 *                a guess and not "offline": {@link #on()} is how a reader says so out loud
 * @param updated when network-control last saw them connected
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
