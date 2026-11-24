package eu.nordtal.season.smp.tunnel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

/** Pure geometric planner for Tunnel Miner start, straight, shift and 90-degree turn steps. */
public final class TunnelPlan {

    /** A rail to place, its explicit shape and the block directly beneath it that must support it. */
    public record RailPlacement(BlockPos pos, Rail.Shape shape, BlockPos supportPos) {}

    /** An upside-down ceiling stair to place, with its outward facing and wall/ceiling material probe positions. */
    public record StairPlacement(BlockPos pos, BlockFace facing, BlockPos sideWallProbe, BlockPos ceilingProbe) {}

    /** An existing ceiling stair to rotate during a 90-degree turn. */
    public record StairRotation(BlockPos pos, BlockFace newFacing) {}

    /** A hanging lantern at {@code pos} when {@code ceilingPos} is solid. */
    public record LanternPlacement(BlockPos pos, BlockPos ceilingPos) {}

    /** Complete, self-contained plan for one tunnel step. */
    public record StepPlan(
            List<BlockPos> carveBlocks,
            List<RailPlacement> rails,
            List<StairPlacement> stairs,
            List<BlockPos> removeBlocks,
            List<StairRotation> rotateStairs,
            List<LanternPlacement> lanterns,
            List<BlockPos> slopeEditBlocks,
            TunnelFrame nextFrame) {

        /** All block positions touched by carving, support placement, slope edits, or 90-degree stair edits. */
        public Set<BlockPos> protectionFootprint() {
            final Set<BlockPos> all = new LinkedHashSet<>(carveBlocks);
            for (final RailPlacement rail : rails) {
                all.add(rail.supportPos());
            }
            all.addAll(removeBlocks);
            for (final StairRotation rotation : rotateStairs) {
                all.add(rotation.pos());
            }
            all.addAll(slopeEditBlocks);
            return all;
        }
    }

    private static final int LANTERN_CADENCE = 8;

    private TunnelPlan() {}

    /** Plans the initial 2-block-deep start dig from {@code startFrame}. */
    public static StepPlan planStart(final TunnelFrame startFrame) {
        return planLinear(startFrame, 0, 0, true);
    }

    /** Plans one extension step from an active {@code frame} through {@code zone}. */
    public static StepPlan planZone(final TunnelFrame frame, final TunnelZone zone) {
        if (zone.isTurn()) {
            return planTurn(frame, zone.isTurnLeft());
        }
        return planLinear(frame, zone.shiftCol(), zone.shiftRow(), false);
    }

    private static StepPlan planLinear(
            final TunnelFrame baseFrame, final int shiftCol, final int shiftRow, final boolean isStart) {
        final BlockPos shiftedOrigin = baseFrame.at(shiftCol, shiftRow, 1);
        final TunnelFrame shifted =
                new TunnelFrame(baseFrame.world(), shiftedOrigin, baseFrame.heading(), baseFrame.length());
        final List<BlockPos> carve = new ArrayList<>(50);
        for (int depth = 1; depth <= 2; depth++) {
            for (int row = 0; row <= 4; row++) {
                for (int col = -2; col <= 2; col++) {
                    carve.add(shifted.at(col, row, depth));
                }
            }
        }
        final List<RailPlacement> rails = buildLinearRails(shifted, shiftCol, isStart);
        final List<StairPlacement> stairs = buildLinearStairs(shifted);
        final List<LanternPlacement> lanterns = new ArrayList<>();
        for (int depth = 1; depth <= 2; depth++) {
            final int stepLength = baseFrame.length() + depth;
            if (stepLength > 0 && stepLength % LANTERN_CADENCE == 0) {
                lanterns.add(new LanternPlacement(shifted.at(0, 4, depth), shifted.at(0, 5, depth)));
            }
        }
        final List<BlockPos> slopeEdits = !isStart && shiftRow > 0 ? List.of(baseFrame.at(0, 0, 0)) : List.of();
        final TunnelFrame next =
                new TunnelFrame(baseFrame.world(), shifted.at(0, 0, 3), shifted.heading(), baseFrame.length() + 2);
        return new StepPlan(
                List.copyOf(carve),
                List.copyOf(rails),
                List.copyOf(stairs),
                List.of(),
                List.of(),
                List.copyOf(lanterns),
                slopeEdits,
                next);
    }

    private static List<RailPlacement> buildLinearRails(
            final TunnelFrame shifted, final int shiftCol, final boolean isStart) {
        final TunnelHeading heading = shifted.heading();
        final List<RailPlacement> rails = new ArrayList<>(3);
        if (!isStart && shiftCol != 0) {
            // Lateral shift: connect previous rail (col = -shiftCol, d=0) sideways at d=1 to centre (col=0, d=1).
            final TunnelHeading towardNew = shiftCol > 0 ? heading.right() : heading.left();
            final TunnelHeading towardOld = towardNew.opposite();
            final BlockPos connector = shifted.at(-shiftCol, 0, 1);
            rails.add(new RailPlacement(
                    connector, TunnelHeading.curvedRail(heading.opposite(), towardNew), connector.offset(0, -1, 0)));
            final BlockPos d1 = shifted.at(0, 0, 1);
            rails.add(new RailPlacement(d1, TunnelHeading.curvedRail(towardOld, heading), d1.offset(0, -1, 0)));
            final BlockPos d2 = shifted.at(0, 0, 2);
            rails.add(new RailPlacement(d2, heading.straightRail(), d2.offset(0, -1, 0)));
            return rails;
        }
        final BlockPos d1 = shifted.at(0, 0, 1);
        final BlockPos d2 = shifted.at(0, 0, 2);
        rails.add(new RailPlacement(d1, heading.straightRail(), d1.offset(0, -1, 0)));
        rails.add(new RailPlacement(d2, heading.straightRail(), d2.offset(0, -1, 0)));
        return rails;
    }

    private static List<StairPlacement> buildLinearStairs(final TunnelFrame shifted) {
        final BlockFace leftOut = shifted.heading().left().blockFace();
        final BlockFace rightOut = shifted.heading().right().blockFace();
        final List<StairPlacement> stairs = new ArrayList<>(4);
        for (int depth = 1; depth <= 2; depth++) {
            stairs.add(new StairPlacement(
                    shifted.at(-2, 4, depth), leftOut, shifted.at(-3, 4, depth), shifted.at(-2, 5, depth)));
            stairs.add(new StairPlacement(
                    shifted.at(2, 4, depth), rightOut, shifted.at(3, 4, depth), shifted.at(2, 5, depth)));
        }
        return stairs;
    }

    private static StepPlan planTurn(final TunnelFrame frame, final boolean turnLeft) {
        final TunnelHeading oldHeading = frame.heading();
        final TunnelHeading newHeading = turnLeft ? oldHeading.left() : oldHeading.right();
        final int turnSign = turnLeft ? -1 : 1;
        final int turnCol = 2 * turnSign;
        final int outerCol = -turnCol;

        final List<BlockPos> carve = new ArrayList<>(75);
        for (int depth = 1; depth <= 3; depth++) {
            for (int row = 0; row <= 4; row++) {
                for (int col = -2; col <= 2; col++) {
                    carve.add(frame.at(col, row, depth));
                }
            }
        }

        final List<RailPlacement> rails = buildTurnRails(frame, oldHeading, newHeading, turnSign, turnCol);
        final List<BlockPos> remove = List.of(frame.at(turnCol, 4, 0));
        final List<StairRotation> rotate = List.of(new StairRotation(
                frame.at(turnCol, 4, -1), oldHeading.opposite().blockFace()));
        final List<StairPlacement> stairs = buildTurnStairs(frame, oldHeading, newHeading, turnSign, outerCol);
        final List<LanternPlacement> lanterns = buildTurnLanterns(frame, rails);
        final TunnelFrame next =
                new TunnelFrame(frame.world(), frame.at(3 * turnSign, 0, 1), newHeading, frame.length() + 3);
        return new StepPlan(List.copyOf(carve), rails, stairs, remove, rotate, lanterns, List.of(), next);
    }

    private static List<RailPlacement> buildTurnRails(
            final TunnelFrame frame,
            final TunnelHeading oldHeading,
            final TunnelHeading newHeading,
            final int turnSign,
            final int turnCol) {
        final BlockPos corner = frame.at(0, 0, 1);
        final BlockPos mid = frame.at(turnSign, 0, 1);
        final BlockPos edge = frame.at(turnCol, 0, 1);
        return List.of(
                new RailPlacement(
                        corner, TunnelHeading.curvedRail(oldHeading.opposite(), newHeading), corner.offset(0, -1, 0)),
                new RailPlacement(mid, newHeading.straightRail(), mid.offset(0, -1, 0)),
                new RailPlacement(edge, newHeading.straightRail(), edge.offset(0, -1, 0)));
    }

    private static List<StairPlacement> buildTurnStairs(
            final TunnelFrame frame,
            final TunnelHeading oldHeading,
            final TunnelHeading newHeading,
            final int turnSign,
            final int outerCol) {
        final List<StairPlacement> stairs = new ArrayList<>(7);
        final BlockFace outerFace = newHeading.opposite().blockFace();
        final BlockFace farFace = oldHeading.blockFace();
        for (int depth = 1; depth <= 2; depth++) {
            stairs.add(new StairPlacement(
                    frame.at(outerCol, 4, depth),
                    outerFace,
                    frame.at(outerCol - turnSign, 4, depth),
                    frame.at(outerCol, 5, depth)));
        }
        for (int col = -2; col <= 2; col++) {
            stairs.add(new StairPlacement(frame.at(col, 4, 3), farFace, frame.at(col, 4, 4), frame.at(col, 5, 3)));
        }
        return List.copyOf(stairs);
    }

    private static List<LanternPlacement> buildTurnLanterns(final TunnelFrame frame, final List<RailPlacement> rails) {
        final List<LanternPlacement> lanterns = new ArrayList<>();
        for (int i = 0; i < rails.size(); i++) {
            final int stepLength = frame.length() + i + 1;
            if (stepLength > 0 && stepLength % LANTERN_CADENCE == 0) {
                final BlockPos r = rails.get(i).pos();
                lanterns.add(new LanternPlacement(r.offset(0, 4, 0), r.offset(0, 5, 0)));
            }
        }
        return List.copyOf(lanterns);
    }
}
