package eu.nordtal.s2.common.online;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code online_count}, as read back.
 *
 * <p>There is no {@code EMPTY} constant here the way {@code NetworkSnapshot} has one: an absent
 * subject is a legitimate answer for this table (proxy has never written it, or not yet
 * this deployment) and {@link OnlineDirectory#current()} leaves it out of the map entirely rather
 * than inventing a placeholder row for it. See {@link OnlineDirectory} for why "no row" and "zero"
 * are kept apart instead of being folded into one type here.
 *
 * @param subject the compose service name, or {@code "proxy"} for the proxy's own total
 * @param players how many players the proxy counted for it at {@code updated} - a real,
 *                trustworthy count, never a stand-in for "unknown"
 * @param updated when proxy wrote this row; the one field a reader needs to decide whether
 *                the count above is still worth showing
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
