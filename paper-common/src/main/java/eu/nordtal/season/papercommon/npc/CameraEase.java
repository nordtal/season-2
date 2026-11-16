package eu.nordtal.season.papercommon.npc;

import org.jspecify.annotations.Nullable;

/**
 * Turns a player's view back to the NPC once the mouse rests, a little each tick.
 * Turns go out as relative rotations, so the mouse still turns the view on top. A turn the player made
 * themselves pauses the ease until the mouse rests again; a turn this class sent does not.
 */
public final class CameraEase {

    static final double EASE = 0.18;
    static final double MAX_STEP = 9.0;
    static final double MOUSE_EPSILON = 0.35;
    static final int REST_TICKS = 12;
    static final int PENDING_TICKS = 10;
    private static final double CLOSE = 0.25;

    private double lastYaw;
    private double lastPitch;
    private double pendingYaw;
    private double pendingPitch;
    private int pendingAge;
    private int rest;

    /** One relative turn to send. */
    public record Turn(double yaw, double pitch) {}

    /** Starts from the view the player has, which counts as a resting mouse. */
    public CameraEase(final double yaw, final double pitch) {
        this.lastYaw = yaw;
        this.lastPitch = pitch;
        this.rest = REST_TICKS;
    }

    /** Takes this tick's view and the angles to the NPC, and returns the turn to send, or null for none. */
    public @Nullable Turn tick(final double yaw, final double pitch, final double targetYaw, final double targetPitch) {
        final double turnedYaw = Gaze.wrap(yaw - lastYaw);
        final double turnedPitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        if (Math.abs(turnedYaw) > 1e-3 || Math.abs(turnedPitch) > 1e-3) {
            if (explains(pendingYaw, turnedYaw) && explains(pendingPitch, turnedPitch)) {
                pendingYaw = consume(pendingYaw, turnedYaw);
                pendingPitch = consume(pendingPitch, turnedPitch);
            } else if (Math.abs(turnedYaw) + Math.abs(turnedPitch) > MOUSE_EPSILON) {
                rest = 0;
                pendingYaw = 0;
                pendingPitch = 0;
                return null;
            }
        }
        if ((pendingYaw != 0 || pendingPitch != 0) && ++pendingAge > PENDING_TICKS) {
            pendingYaw = 0;
            pendingPitch = 0;
        }
        rest++;
        if (rest < REST_TICKS) {
            return null;
        }
        final double leftYaw = Gaze.wrap(targetYaw - (yaw + pendingYaw));
        final double leftPitch = targetPitch - (pitch + pendingPitch);
        if (Math.abs(leftYaw) < CLOSE && Math.abs(leftPitch) < CLOSE) {
            return null;
        }
        final Turn turn = new Turn(step(leftYaw), step(leftPitch));
        pendingYaw += turn.yaw();
        pendingPitch += turn.pitch();
        pendingAge = 0;
        return turn;
    }

    private static boolean explains(final double pending, final double turned) {
        if (Math.abs(turned) <= MOUSE_EPSILON) {
            return true;
        }
        return Math.signum(pending) == Math.signum(turned) && Math.abs(turned) <= Math.abs(pending) + MOUSE_EPSILON;
    }

    private static double consume(final double pending, final double turned) {
        if (pending == 0) {
            return 0;
        }
        final double left = pending - turned;
        return Math.signum(left) == Math.signum(pending) ? left : 0;
    }

    static double step(final double left) {
        if (Math.abs(left) < CLOSE) {
            return 0;
        }
        double step = left * EASE;
        if (Math.abs(step) < CLOSE) {
            step = Math.signum(left) * Math.min(CLOSE, Math.abs(left));
        }
        return Math.max(-MAX_STEP, Math.min(MAX_STEP, step));
    }
}
