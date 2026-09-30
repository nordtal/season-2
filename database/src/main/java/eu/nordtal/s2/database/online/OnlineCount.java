package eu.nordtal.s2.database.online;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code online_count}, as read back.
 *
 * @param subject the compose service name, or {@code "proxy"} for the proxy's own total
 * @param players how many players the proxy counted for it at {@code updated}
 * @param updated when proxy wrote this row, which decides whether the count is still worth showing
 */
public record OnlineCount(String subject, int players, Instant updated) {

    public OnlineCount {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(updated, "updated");
        if (players < 0) {
            throw new IllegalArgumentException("players must not be negative, was " + players);
        }
    }
}
