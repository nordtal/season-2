package eu.nordtal.season.smp.tunnel;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.jspecify.annotations.Nullable;

/** Horizontal cardinal heading of a tunnel, with its forward/right axes, yaw and rail shapes. */
public enum TunnelHeading {
    NORTH(0, -1, 1, 0, 0f, BlockFace.NORTH),
    EAST(1, 0, 0, 1, 90f, BlockFace.EAST),
    SOUTH(0, 1, -1, 0, 180f, BlockFace.SOUTH),
    WEST(-1, 0, 0, -1, -90f, BlockFace.WEST);

    private final int forwardX;
    private final int forwardZ;
    private final int rightX;
    private final int rightZ;
    private final float overlayYaw;
    private final BlockFace blockFace;

    TunnelHeading(
            final int forwardX,
            final int forwardZ,
            final int rightX,
            final int rightZ,
            final float overlayYaw,
            final BlockFace blockFace) {
        this.forwardX = forwardX;
        this.forwardZ = forwardZ;
        this.rightX = rightX;
        this.rightZ = rightZ;
        this.overlayYaw = overlayYaw;
        this.blockFace = blockFace;
    }

    public int forwardX() {
        return forwardX;
    }

    public int forwardZ() {
        return forwardZ;
    }

    public int rightX() {
        return rightX;
    }

    public int rightZ() {
        return rightZ;
    }

    /** Yaw in degrees for an item_display on the front wall facing back toward the player. */
    public float overlayYaw() {
        return overlayYaw;
    }

    public BlockFace blockFace() {
        return blockFace;
    }

    public TunnelHeading left() {
        return switch (this) {
            case NORTH -> WEST;
            case WEST -> SOUTH;
            case SOUTH -> EAST;
            case EAST -> NORTH;
        };
    }

    public TunnelHeading right() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
        };
    }

    public TunnelHeading opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    /** Returns the tunnel heading for a horizontal cardinal face, or null for non-horizontal faces. */
    public static @Nullable TunnelHeading fromBlockFace(final BlockFace face) {
        return switch (face) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> null;
        };
    }

    /** Returns the flat straight rail shape aligned with this heading. */
    public Rail.Shape straightRail() {
        return this == NORTH || this == SOUTH ? Rail.Shape.NORTH_SOUTH : Rail.Shape.EAST_WEST;
    }

    /** Returns the curved rail shape connecting {@code from} and {@code to} (adjacent cardinal sides of the block). */
    public static Rail.Shape curvedRail(final TunnelHeading from, final TunnelHeading to) {
        if ((from == SOUTH && to == EAST) || (from == EAST && to == SOUTH)) {
            return Rail.Shape.SOUTH_EAST;
        }
        if ((from == SOUTH && to == WEST) || (from == WEST && to == SOUTH)) {
            return Rail.Shape.SOUTH_WEST;
        }
        if ((from == NORTH && to == WEST) || (from == WEST && to == NORTH)) {
            return Rail.Shape.NORTH_WEST;
        }
        if ((from == NORTH && to == EAST) || (from == EAST && to == NORTH)) {
            return Rail.Shape.NORTH_EAST;
        }
        return from.straightRail();
    }
}
