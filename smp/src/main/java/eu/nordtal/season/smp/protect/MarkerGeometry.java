package eu.nordtal.season.smp.protect;

import eu.nordtal.season.smp.config.SpawnExceptionSpec;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;

/**
 * Pure geometry for placing right-click display markers flush on the large face(s) of doors and trapdoors.
 * Computes exact block-relative coordinates and fixed billboard angles for both open and closed states.
 */
final class MarkerGeometry {

    /** Door and trapdoor leaf thickness in blocks (3/16). */
    static final double SLAB = 3.0 / 16.0;

    /** Small outward normal offset in blocks to prevent z-fighting with the block face. */
    static final double OFFSET = 0.008;

    /** TextDisplay scale producing an approximately 0.25-block tall marker for an 8 px glyph. */
    static final float SCALE = 1.25f;

    /** Vertical translation centring the 8 px baseline glyph on the display entity's origin. */
    static final float GLYPH_Y_OFFSET = -0.1f;

    /** Roughly 16-block view distance (Paper default viewRange 1.0 corresponds to 64 blocks). */
    static final float VIEW_RANGE = 0.25f;

    /** Placement of one display marker relative to the entry's anchor block (lower half for doors). */
    record Placement(double dx, double dy, double dz, float yaw, float pitch, BlockFace outward) {}

    private MarkerGeometry() {}

    /** Computes the display placements for a door given its current and default open states. */
    static List<Placement> forDoor(
            final BlockFace facing,
            final Door.Hinge hinge,
            final boolean open,
            final boolean defaultOpen,
            final SpawnExceptionSpec.IconSide iconSide) {
        if (iconSide == SpawnExceptionSpec.IconSide.NONE) {
            return List.of();
        }
        final BlockFace currentFront = doorFrontFace(facing, hinge, open, defaultOpen);
        final List<BlockFace> faces = selectedFaces(currentFront, iconSide);
        final BlockFace edge = doorEdge(facing, hinge, open);
        final List<Placement> out = new ArrayList<>(faces.size());
        for (final BlockFace face : faces) {
            out.add(verticalPlacement(edge, face, 1.5));
        }
        return List.copyOf(out);
    }

    /** Computes the display placements for a trapdoor given its current and default open states. */
    static List<Placement> forTrapdoor(
            final BlockFace facing,
            final Bisected.Half half,
            final boolean open,
            final boolean defaultOpen,
            final SpawnExceptionSpec.IconSide iconSide) {
        if (iconSide == SpawnExceptionSpec.IconSide.NONE) {
            return List.of();
        }
        final BlockFace currentFront = trapdoorFrontFace(facing, half, open, defaultOpen);
        final List<BlockFace> faces = selectedFaces(currentFront, iconSide);
        final List<Placement> out = new ArrayList<>(faces.size());
        if (!open) {
            for (final BlockFace face : faces) {
                out.add(horizontalPlacement(half, face));
            }
        } else {
            final BlockFace edge = facing.getOppositeFace();
            for (final BlockFace face : faces) {
                out.add(verticalPlacement(edge, face, 0.5));
            }
        }
        return List.copyOf(out);
    }

    /** Returns the world direction currently occupied by the door's {@code FRONT} face. */
    private static BlockFace doorFrontFace(
            final BlockFace facing, final Door.Hinge hinge, final boolean open, final boolean defaultOpen) {
        final BlockFace defaultFront = defaultDoorFront(facing, defaultOpen);
        if (open == defaultOpen) {
            return defaultFront;
        }
        final boolean clockwiseOnOpen = rotatesClockwiseOnOpen(facing, hinge);
        return open ? rotate(defaultFront, clockwiseOnOpen) : rotate(defaultFront, !clockwiseOnOpen);
    }

    private static BlockFace defaultDoorFront(final BlockFace facing, final boolean defaultOpen) {
        final boolean northSouthClosed = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
        final boolean northSouthDefault = defaultOpen != northSouthClosed;
        return northSouthDefault ? BlockFace.NORTH : BlockFace.EAST;
    }

    private static boolean rotatesClockwiseOnOpen(final BlockFace facing, final Door.Hinge hinge) {
        return switch (facing) {
            case NORTH -> hinge == Door.Hinge.LEFT;
            case SOUTH -> hinge == Door.Hinge.RIGHT;
            case EAST, WEST -> hinge == Door.Hinge.RIGHT;
            default -> true;
        };
    }

    private static BlockFace doorEdge(final BlockFace facing, final Door.Hinge hinge, final boolean open) {
        if (!open) {
            return facing.getOppositeFace();
        }
        return switch (facing) {
            case NORTH -> hinge == Door.Hinge.LEFT ? BlockFace.WEST : BlockFace.EAST;
            case SOUTH -> hinge == Door.Hinge.LEFT ? BlockFace.EAST : BlockFace.WEST;
            case EAST -> hinge == Door.Hinge.LEFT ? BlockFace.NORTH : BlockFace.SOUTH;
            case WEST -> hinge == Door.Hinge.LEFT ? BlockFace.SOUTH : BlockFace.NORTH;
            default -> BlockFace.SOUTH;
        };
    }

    /** Returns the world direction currently occupied by the trapdoor's {@code FRONT} face. */
    private static BlockFace trapdoorFrontFace(
            final BlockFace facing, final Bisected.Half half, final boolean open, final boolean defaultOpen) {
        final BlockFace uprightOfUp = half == Bisected.Half.BOTTOM ? facing : facing.getOppositeFace();
        if (!defaultOpen) {
            return open ? uprightOfUp : BlockFace.UP;
        }
        final BlockFace defaultFront =
                (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) ? BlockFace.NORTH : BlockFace.EAST;
        if (open) {
            return defaultFront;
        }
        return defaultFront == uprightOfUp ? BlockFace.UP : BlockFace.DOWN;
    }

    private static List<BlockFace> selectedFaces(final BlockFace front, final SpawnExceptionSpec.IconSide iconSide) {
        return switch (iconSide) {
            case BOTH -> List.of(front, front.getOppositeFace());
            case FRONT -> List.of(front);
            case BACK -> List.of(front.getOppositeFace());
            case NONE -> List.of();
        };
    }

    private static Placement verticalPlacement(final BlockFace edge, final BlockFace outward, final double dy) {
        final double dx;
        final double dz;
        switch (edge) {
            case NORTH -> {
                dx = 0.5;
                dz = outward == BlockFace.NORTH ? -OFFSET : SLAB + OFFSET;
            }
            case SOUTH -> {
                dx = 0.5;
                dz = outward == BlockFace.NORTH ? 1.0 - SLAB - OFFSET : 1.0 + OFFSET;
            }
            case WEST -> {
                dx = outward == BlockFace.WEST ? -OFFSET : SLAB + OFFSET;
                dz = 0.5;
            }
            case EAST -> {
                dx = outward == BlockFace.WEST ? 1.0 - SLAB - OFFSET : 1.0 + OFFSET;
                dz = 0.5;
            }
            default -> {
                dx = 0.5;
                dz = 0.5;
            }
        }
        return new Placement(dx, dy, dz, yawOf(outward), 0f, outward);
    }

    private static Placement horizontalPlacement(final Bisected.Half half, final BlockFace outward) {
        final double dy = half == Bisected.Half.BOTTOM
                ? (outward == BlockFace.UP ? SLAB + OFFSET : -OFFSET)
                : (outward == BlockFace.UP ? 1.0 + OFFSET : 1.0 - SLAB - OFFSET);
        final float pitch = outward == BlockFace.UP ? -90f : 90f;
        return new Placement(0.5, dy, 0.5, 0f, pitch, outward);
    }

    private static float yawOf(final BlockFace outward) {
        return switch (outward) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST -> -90f;
            case WEST -> 90f;
            default -> 0f;
        };
    }

    /** Rotates a horizontal cardinal face by 90 degrees around Y (viewed from above). */
    private static BlockFace rotate(final BlockFace face, final boolean clockwise) {
        return switch (face) {
            case NORTH -> clockwise ? BlockFace.EAST : BlockFace.WEST;
            case EAST -> clockwise ? BlockFace.SOUTH : BlockFace.NORTH;
            case SOUTH -> clockwise ? BlockFace.WEST : BlockFace.EAST;
            case WEST -> clockwise ? BlockFace.NORTH : BlockFace.SOUTH;
            default -> face;
        };
    }
}
