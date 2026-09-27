package eu.nordtal.s2.hungergames;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Everything about the running game that lives only in memory, not in the {@code hg_*} rows.
 *
 * {@code WorldBorder} exposes no in-transition state, so the shrink is tracked here, reset per game.
 */
public final class GameState {

    private volatile @Nullable UUID gameId;
    /** {@code true} from release, the end of the countdown, onward. */
    private volatile boolean running;

    private volatile int effectiveParticipants;
    private volatile double borderStep;

    /** {@code true} while a border shrink (death-triggered or passive) is in flight. */
    private volatile boolean shrinking;
    /** The diameter the current shrink is heading toward. Meaningless while {@link #shrinking} is false. */
    private volatile double shrinkTarget;
    /** When the current shrink should finish, which decides whether a new death extends it. */
    private volatile @Nullable Instant shrinkEndsAt;
    /** {@code true} when the in-flight shrink is the slow passive one rather than a death-triggered one. */
    private volatile boolean passiveShrink;

    /** The last time any player died or was eliminated, which drives the quiet-period timer. */
    private volatile @Nullable Instant lastDeathAt;

    /** Minecraft UUID -> the instant PvP protection ends for that player. */
    private final Map<UUID, Instant> protectedUntil = new ConcurrentHashMap<>();

    public @Nullable UUID gameId() {
        return gameId;
    }

    public void reset(final UUID newGameId, final int newEffectiveParticipants, final double step) {
        this.gameId = newGameId;
        this.running = false;
        this.effectiveParticipants = newEffectiveParticipants;
        this.borderStep = step;
        this.shrinking = false;
        this.shrinkTarget = 0;
        this.shrinkEndsAt = null;
        this.passiveShrink = false;
        this.lastDeathAt = Instant.now();
        this.protectedUntil.clear();
    }

    /** Marks the game as released, once, when the countdown finishes. */
    public void release() {
        this.running = true;
    }

    /** Returns whether the countdown has finished; false during COUNTDOWN. */
    public boolean isRunning() {
        return running;
    }

    /** Clears all state once a game ends, so a stale gameId cannot leak into the next game. */
    public void clear() {
        this.gameId = null;
        this.running = false;
    }

    public int effectiveParticipants() {
        return effectiveParticipants;
    }

    public double borderStep() {
        return borderStep;
    }

    public boolean isShrinking() {
        return shrinking;
    }

    public double shrinkTarget() {
        return shrinkTarget;
    }

    public @Nullable Instant shrinkEndsAt() {
        return shrinkEndsAt;
    }

    public boolean isPassiveShrink() {
        return passiveShrink;
    }

    public void beginShrink(final double target, final Instant endsAt, final boolean passive) {
        this.shrinking = true;
        this.shrinkTarget = target;
        this.shrinkEndsAt = endsAt;
        this.passiveShrink = passive;
    }

    public void endShrink() {
        this.shrinking = false;
        this.shrinkEndsAt = null;
    }

    public @Nullable Instant lastDeathAt() {
        return lastDeathAt;
    }

    public void markDeath(final Instant when) {
        this.lastDeathAt = when;
    }

    public void protect(final UUID mcUuid, final Instant until) {
        protectedUntil.put(mcUuid, until);
    }

    public boolean isProtected(final UUID mcUuid, final Instant now) {
        final Instant until = protectedUntil.get(mcUuid);
        return until != null && now.isBefore(until);
    }

    public void clearProtection(final UUID mcUuid) {
        protectedUntil.remove(mcUuid);
    }
}
