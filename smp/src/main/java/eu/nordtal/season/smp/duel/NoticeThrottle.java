package eu.nordtal.season.smp.duel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Lets a notice through to a player at most once per interval, however often they cause it. */
final class NoticeThrottle {

    private final long intervalMillis;

    private final Map<UUID, Long> lastNotice = new HashMap<>();

    NoticeThrottle(final long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /** Returns whether {@code player} gets the notice at {@code now}, and counts it if so. */
    boolean allows(final UUID player, final long now) {
        final Long last = lastNotice.get(player);
        if (last != null && now - last < intervalMillis) {
            return false;
        }
        lastNotice.put(player, now);
        return true;
    }

    void forget(final UUID player) {
        lastNotice.remove(player);
    }
}
