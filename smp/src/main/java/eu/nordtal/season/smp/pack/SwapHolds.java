package eu.nordtal.season.smp.pack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;

/**
 * The players a live swap holds still until their client is done with its packs, and when each swap is done.
 * Not thread-safe: {@link PackSwap} uses it on the main thread only.
 */
final class SwapHolds {

    /** A player's answer that ended their hold. {@code last} says whether it was the last of its swap. */
    record Answered(long request, long millis, boolean last) {}

    /** A held player's swap, with the packs their client is not done with yet. */
    private record Held(long request, Set<UUID> packs, long sent) {}

    private final Map<UUID, Held> held = new HashMap<>();

    /**
     * Holds {@code player} for the {@code packs} of {@code request}, sent at {@code nanos}.
     *
     * @return the older swap the player was still held for, when this was its last player, so it can be settled
     */
    OptionalLong hold(final UUID player, final long request, final Set<UUID> packs, final long nanos) {
        final Held before = held.put(player, new Held(request, new HashSet<>(packs), nanos));
        return before == null || before.request() == request ? OptionalLong.empty() : doneIfNoneLeft(before.request());
    }

    /** Returns whether {@code player} is held. */
    boolean holds(final UUID player) {
        return held.containsKey(player);
    }

    /** Ends the hold of {@code player} once their client's {@code status} is final for every pack they wait for. */
    Optional<Answered> answer(final UUID player, final UUID pack, final Status status, final long nanos) {
        final Held mine = held.get(player);
        if (mine == null
                || !isFinal(status)
                || !mine.packs().remove(pack)
                || !mine.packs().isEmpty()) {
            return Optional.empty();
        }
        held.remove(player);
        return Optional.of(new Answered(
                mine.request(),
                (nanos - mine.sent()) / 1_000_000,
                doneIfNoneLeft(mine.request()).isPresent()));
    }

    /** Ends the hold of a player who left, and returns their swap when they were its last player. */
    OptionalLong leave(final UUID player) {
        final Held mine = held.remove(player);
        return mine == null ? OptionalLong.empty() : doneIfNoneLeft(mine.request());
    }

    /** Lets go of every player {@code request} still holds, and returns how many there were. */
    int release(final long request) {
        final int before = held.size();
        held.values().removeIf(mine -> mine.request() == request);
        return before - held.size();
    }

    static boolean isFinal(final Status status) {
        return switch (status) {
            case SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> true;
            case ACCEPTED, DOWNLOADED -> false;
        };
    }

    private OptionalLong doneIfNoneLeft(final long request) {
        return held.values().stream().anyMatch(mine -> mine.request() == request)
                ? OptionalLong.empty()
                : OptionalLong.of(request);
    }
}
