package eu.nordtal.s2.hungergames.game;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything about the currently running game that lives only in memory, and is not one of the
 * {@code hg_*} rows themselves.
 * <p>
 * {@code org.bukkit.WorldBorder} does not expose "am I mid-transition" (verified against Paper
 * 26.2's actual interface: {@code getSize()}, {@code setSize(double, long)} and
 * {@code changeSize(double, long)} are the whole surface - there is no "current target" or
 * "time remaining" getter), so the plugin has to track shrink state itself. This is that state,
 * held once per running game and reset when a new one starts.
 * </p>
 */
public final class GameState {

    private volatile UUID gameId;
    /** {@code true} only from release (end of countdown) onward - see {@link #release()}. */
    private volatile boolean running;
    private volatile int effectiveParticipants;
    private volatile double borderStep;

    /** {@code true} while a border shrink (death-triggered or passive) is in flight. */
    private volatile boolean shrinking;
    /** The diameter the current shrink is heading toward. Meaningless while {@link #shrinking} is false. */
    private volatile double shrinkTarget;
    /** When the current shrink is expected to finish - used to decide whether a new death extends it. */
    private volatile Instant shrinkEndsAt;
    /** {@code true} when the in-flight shrink is the slow passive one rather than a death-triggered one. */
    private volatile boolean passiveShrink;

    /** The last time any player died or was eliminated - drives the quiet-period timer. */
    private volatile Instant lastDeathAt;

    /** Minecraft UUID -> the instant PvP protection ends for that player. */
    private final Map<UUID, Instant> protectedUntil = new ConcurrentHashMap<>();

    public UUID gameId() {
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

    /** Marks the game as released - called once, when the countdown finishes. */
    public void release() {
        this.running = true;
    }

    /** @return whether the game has been released (countdown finished); false during COUNTDOWN */
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

    public Instant shrinkEndsAt() {
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

    public Instant lastDeathAt() {
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
