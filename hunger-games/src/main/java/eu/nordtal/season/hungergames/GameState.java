package eu.nordtal.season.hungergames;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** The state of the running game that is not stored in the {@code hg_*} tables. */
public final class GameState {

    private volatile @Nullable UUID gameId;
    /** Whether the countdown has finished. */
    private volatile boolean running;

    private volatile int effectiveParticipants;
    private volatile double borderStep;

    /** Whether the border is shrinking. */
    private volatile boolean shrinking;
    /** The diameter the current shrink ends at. */
    private volatile double shrinkTarget;
    /** The end of the current shrink. */
    private volatile @Nullable Instant shrinkEndsAt;
    /** Whether the current shrink is the passive one and not the one a death starts. */
    private volatile boolean passiveShrink;

    /** The time of the last death or elimination. */
    private volatile @Nullable Instant lastDeathAt;

    /** The end of the PvP protection of each player, by Minecraft UUID. */
    private final Map<UUID, Instant> protectedUntil = new ConcurrentHashMap<>();

    public @Nullable UUID gameId() {
        return gameId;
    }

    public void reset(final UUID newGameId, final int newEffectiveParticipants, final double step, final Instant now) {
        this.gameId = newGameId;
        this.running = false;
        this.effectiveParticipants = newEffectiveParticipants;
        this.borderStep = step;
        this.shrinking = false;
        this.shrinkTarget = 0;
        this.shrinkEndsAt = null;
        this.passiveShrink = false;
        this.lastDeathAt = now;
        this.protectedUntil.clear();
    }

    /** Sets the game to running. */
    public void release() {
        this.running = true;
    }

    /** Returns whether the countdown has finished. */
    public boolean isRunning() {
        return running;
    }

    /** Deletes the game id and sets the game to not running. */
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
