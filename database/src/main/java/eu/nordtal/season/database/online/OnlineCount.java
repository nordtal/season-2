package eu.nordtal.season.database.online;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code online_count}.
 *
 * @param subject a compose service name, or {@code "proxy"} for the proxy's total
 * @param updated when the proxy wrote the row, which decides whether the count is still shown
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
