package eu.nordtal.season.papercommon.npc;

import org.jspecify.annotations.Nullable;

/** Where an NPC's head and body face while it looks at a player near it, or back home without one. */
public final class HeadTurn {

    static final double EASE = 0.2;
    static final double MOST_PITCH = 40.0;
    static final double MOST_BODY = 45.0;

    private final double home;
    private double yaw;
    private double pitch;

    /** The angles to set this tick. */
    public record Pose(float headYaw, float headPitch, float bodyYaw) {}

    /** The offsets from the NPC's eye to a player's eye. */
    public record Offset(double x, double y, double z) {}

    public HeadTurn(final double home) {
        this.home = home;
        this.yaw = home;
    }

    /** Eases one tick towards the player at the offsets from the NPC's eye, or home when {@code toward} is null. */
    public Pose tick(final @Nullable Offset toward) {
        final double targetYaw = toward == null ? home : Gaze.yawTo(toward.x(), toward.z());
        final double targetPitch = toward == null
                ? 0.0
                : Math.max(-MOST_PITCH, Math.min(MOST_PITCH, Gaze.pitchTo(toward.x(), toward.y(), toward.z())));
        yaw = Gaze.wrap(yaw + Gaze.wrap(targetYaw - yaw) * EASE);
        pitch = pitch + (targetPitch - pitch) * EASE;
        final double body = home + Math.max(-MOST_BODY, Math.min(MOST_BODY, Gaze.wrap(yaw - home)));
        return new Pose((float) yaw, (float) pitch, (float) Gaze.wrap(body));
    }
}
