package eu.nordtal.season.smp.tunnel;

import org.jspecify.annotations.Nullable;

/**
 * Position, heading, length and turn-lock state of a 5x5 tunnel front face.
 * {@code origin} is the bottom-centre block of the wall face: {@code (col=0, row=0, depth=1)}.
 */
public record TunnelFrame(String world, BlockPos origin, TunnelHeading heading, int length, boolean turnsLocked) {

    /** Creates a frame with turns enabled. */
    public TunnelFrame(final String world, final BlockPos origin, final TunnelHeading heading, final int length) {
        this(world, origin, heading, length, false);
    }

    /** Local coordinates relative to a tunnel frame: col -2..+2, row 0..4, depth 1 at the front wall. */
    public record Local(int col, int row, int depth) {}

    /** Maximum distance from the active face centre before an active tunnel cancels. */
    public static final double MAX_ACTIVE_DISTANCE = 12.0;

    /** Converts local {@code (col, row, depth)} to world block coordinates ({@code depth = 1} is the front wall). */
    public BlockPos at(final int col, final int row, final int depth) {
        final int step = depth - 1;
        return new BlockPos(
                origin.x() + col * heading.rightX() + step * heading.forwardX(),
                origin.y() + row,
                origin.z() + col * heading.rightZ() + step * heading.forwardZ());
    }

    /** Maps a world block position into local frame coordinates. */
    public Local locate(final BlockPos pos) {
        final int dx = pos.x() - origin.x();
        final int dy = pos.y() - origin.y();
        final int dz = pos.z() - origin.z();
        final int col = dx * heading.rightX() + dz * heading.rightZ();
        final int depth = dx * heading.forwardX() + dz * heading.forwardZ() + 1;
        return new Local(col, dy, depth);
    }

    /** Returns whether {@code pos} lies on this frame's 5x5 front wall ({@code depth == 1}). */
    public boolean isFrontFace(final BlockPos pos) {
        final Local local = locate(pos);
        return local.depth() == 1 && local.col() >= -2 && local.col() <= 2 && local.row() >= 0 && local.row() <= 4;
    }

    /** Returns the zone on this frame's front face ({@code depth == 1}) for {@code pos}, or null if outside. */
    public @Nullable TunnelZone zoneAt(final BlockPos pos) {
        final Local local = locate(pos);
        return local.depth() == 1 ? TunnelZone.at(local.col(), local.row()) : null;
    }

    /** Squared distance from {@code (x, y, z)} to the centre of the 5x5 active front face. */
    public double distanceSquaredToFaceCentre(final double x, final double y, final double z) {
        final double cx = origin.x() + 0.5;
        final double cy = origin.y() + 2.5;
        final double cz = origin.z() + 0.5;
        final double dx = x - cx;
        final double dy = y - cy;
        final double dz = z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns whether {@code (x, y, z)} lies within the 12-block active tunnel radius. */
    public boolean withinActiveRange(final double x, final double y, final double z) {
        return distanceSquaredToFaceCentre(x, y, z) <= MAX_ACTIVE_DISTANCE * MAX_ACTIVE_DISTANCE;
    }

    /** Computes the start origin for {@code target}, or null if grounded outside {@code [floor, floor+4]}. */
    public static @Nullable BlockPos startOrigin(final BlockPos target, final boolean onGround, final double playerY) {
        if (onGround) {
            final int playerFloorY = (int) Math.floor(playerY);
            if (target.y() < playerFloorY || target.y() > playerFloorY + 4) {
                return null;
            }
            return new BlockPos(target.x(), playerFloorY, target.z());
        }
        return new BlockPos(target.x(), target.y() - 2, target.z());
    }
}
