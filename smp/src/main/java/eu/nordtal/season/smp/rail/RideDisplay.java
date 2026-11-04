package eu.nordtal.season.smp.rail;

import eu.nordtal.season.packrendering.hud.BossBarAdvances;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Decides what each rider is told about their ride: when it departs, what the HUD shows and what arrived means.
 * It knows riders and stations by id only; {@link RideFeedback} says and draws what it decides.
 */
final class RideDisplay {

    /** How long a full bar stays in the HUD after the arrival, so at least one HUD frame draws it. */
    static final Duration HELD = Duration.ofMillis(1500);

    /** What the HUD shows of one ride: the station, the share done from 0 to 1, and the whole metres left. */
    record View(String station, double done, long metres) {}

    /** One rider's ride; {@code heldUntil} is set once it arrived and only the full bar is still shown. */
    private record Shown(
            UUID station,
            String name,
            RideProgress progress,
            @Nullable Long heldUntil) {}

    private final Map<UUID, Shown> shown = new HashMap<>();

    /**
     * Takes in a planned way and returns the station to announce as the departure, or nothing for a replan.
     * A station other than the one shown, at the start or picked on the way, is a new ride.
     */
    Optional<String> planned(final UUID rider, final UUID station, final String name, final double left) {
        final @Nullable Shown now = shown.get(rider);
        if (now != null && now.heldUntil() == null && now.station().equals(station)) {
            now.progress().replanned(left);
            return Optional.empty();
        }
        shown.put(rider, new Shown(station, name, new RideProgress(left), null));
        return Optional.of(name);
    }

    void moved(final UUID rider, final double left) {
        final @Nullable Shown now = shown.get(rider);
        if (now != null && now.heldUntil() == null) {
            now.progress().moved(left);
        }
    }

    /** Fills the bar and holds it until {@link #HELD} after {@code at}, in epoch milliseconds. */
    void arrived(final UUID rider, final UUID station, final String name, final long at) {
        final RideProgress full = new RideProgress(0);
        full.arrived();
        shown.put(rider, new Shown(station, name, full, at + HELD.toMillis()));
    }

    /** Ends a ride that did not arrive; a held arrival stays until its time is up. */
    void ended(final UUID rider) {
        final @Nullable Shown now = shown.get(rider);
        if (now != null && now.heldUntil() == null) {
            shown.remove(rider);
        }
    }

    /** Returns what the HUD shows of {@code rider}'s ride at {@code at}, in epoch milliseconds. */
    Optional<View> view(final UUID rider, final long at) {
        final @Nullable Shown now = shown.get(rider);
        if (now == null) {
            return Optional.empty();
        }
        final @Nullable Long until = now.heldUntil();
        if (until != null && at >= until) {
            shown.remove(rider);
            return Optional.empty();
        }
        return Optional.of(new View(
                drawable(now.name()), now.progress().done(), now.progress().metres()));
    }

    /** Returns {@code name} with each character the HUD font lacks drawn as a question mark instead of a box. */
    static String drawable(final String name) {
        final StringBuilder out = new StringBuilder();
        name.codePoints()
                .forEach(codePoint -> out.appendCodePoint(BossBarAdvances.covers(codePoint) ? codePoint : '?'));
        return out.toString();
    }
}
