package eu.nordtal.season.smp.tunnel;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Executes a {@link TunnelPlan.StepPlan} in a Bukkit world: carving, fluids, supports, rails, stairs and lanterns. */
final class TunnelExecutor {

    private final Consumer<Block> railChanged;
    private final Predicate<Block> isStation;
    private final BiPredicate<Block, Boolean> removeStation;
    private final DoubleSupplier dropRoll;

    TunnelExecutor(
            final Consumer<Block> railChanged,
            final Predicate<Block> isStation,
            final BiPredicate<Block, Boolean> removeStation) {
        this(
                railChanged,
                isStation,
                removeStation,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    TunnelExecutor(
            final Consumer<Block> railChanged,
            final Predicate<Block> isStation,
            final BiPredicate<Block, Boolean> removeStation,
            final DoubleSupplier dropRoll) {
        this.railChanged = railChanged;
        this.isStation = isStation;
        this.removeStation = removeStation;
        this.dropRoll = dropRoll;
    }

    /** Executes {@code plan} for {@code player} with {@code tool} after protections have already passed. */
    void execute(
            final Player player,
            final World world,
            final ItemStack tool,
            final TunnelPlan.StepPlan plan,
            final @Nullable TunnelFrame previousFrame,
            final @Nullable TunnelZone zone) {
        final boolean creative = player.getGameMode() == GameMode.CREATIVE;
        final Map<Material, Integer> rockCounts = new EnumMap<>(Material.class);
        final Set<BlockPos> skipped = new HashSet<>();

        carve(world, tool, creative, plan, rockCounts, skipped);
        final @Nullable Material dominantRock = dominantRock(rockCounts);
        final World.Environment env = world.getEnvironment();

        placeRails(world, plan, previousFrame, zone, dominantRock, env, skipped);
        applyTurnStairEdits(world, plan, skipped);
        placeStairs(world, plan, dominantRock, env, skipped);
        placeLanterns(world, plan, skipped);
    }

    private void carve(
            final World world,
            final ItemStack tool,
            final boolean creative,
            final TunnelPlan.StepPlan plan,
            final Map<Material, Integer> rockCounts,
            final Set<BlockPos> skipped) {
        final World.Environment env = world.getEnvironment();
        for (final BlockPos pos : plan.carveBlocks()) {
            final Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            final Material type = block.getType();
            if (type.isAir()) {
                continue;
            }
            if (block.isLiquid()) {
                block.setType(Material.AIR, false);
                continue;
            }
            if (isSkippedBlock(block)) {
                skipped.add(pos);
                continue;
            }
            if (removeStation.test(block, !creative)) {
                continue;
            }
            if (TunnelMaterials.stairFor(type) != null) {
                rockCounts.merge(type, 1, Integer::sum);
            }
            if (creative) {
                block.setType(Material.AIR, false);
            } else if (TunnelMaterials.isReducedDropRock(type, env)
                    && !TunnelMaterials.shouldDropInTunnel(type, env, dropRoll.getAsDouble())) {
                block.setType(Material.AIR, false);
            } else {
                block.breakNaturally(tool, false, true);
                if (block.isLiquid()) {
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void placeRails(
            final World world,
            final TunnelPlan.StepPlan plan,
            final @Nullable TunnelFrame previousFrame,
            final @Nullable TunnelZone zone,
            final @Nullable Material dominantRock,
            final World.Environment env,
            final Set<BlockPos> skipped) {
        final boolean verticalUp = zone == TunnelZone.TOP;
        final boolean verticalDown = zone == TunnelZone.BOTTOM;
        boolean firstRailPlaced = false;
        for (int i = 0; i < plan.rails().size(); i++) {
            final TunnelPlan.RailPlacement rail = plan.rails().get(i);
            if (skipped.contains(rail.pos())) {
                continue;
            }
            final Block support = world.getBlockAt(
                    rail.supportPos().x(),
                    rail.supportPos().y(),
                    rail.supportPos().z());
            if (!ensureSupport(support, dominantRock, env)) {
                continue;
            }
            final Block block =
                    world.getBlockAt(rail.pos().x(), rail.pos().y(), rail.pos().z());
            final Rail data = (Rail) Material.RAIL.createBlockData();
            if (verticalDown && i == 0 && previousFrame != null) {
                data.setShape(ascending(previousFrame.heading().opposite()));
            } else {
                data.setShape(rail.shape());
            }
            block.setBlockData(data, false);
            railChanged.accept(block);
            if (i == 0) {
                firstRailPlaced = true;
            }
        }
        if (verticalUp && firstRailPlaced && previousFrame != null) {
            final BlockPos prevPos = previousFrame.at(0, 0, 0);
            final Block prevBlock = world.getBlockAt(prevPos.x(), prevPos.y(), prevPos.z());
            if (!isStation.test(prevBlock)
                    && prevBlock.getType() == Material.RAIL
                    && prevBlock.getBlockData() instanceof final Rail prevRail) {
                prevRail.setShape(ascending(previousFrame.heading()));
                prevBlock.setBlockData(prevRail, false);
                railChanged.accept(prevBlock);
            }
        }
    }

    private static Rail.Shape ascending(final TunnelHeading direction) {
        return switch (direction) {
            case NORTH -> Rail.Shape.ASCENDING_NORTH;
            case SOUTH -> Rail.Shape.ASCENDING_SOUTH;
            case EAST -> Rail.Shape.ASCENDING_EAST;
            case WEST -> Rail.Shape.ASCENDING_WEST;
        };
    }

    private static boolean ensureSupport(
            final Block support, final @Nullable Material dominantRock, final World.Environment env) {
        if (support.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL)) {
            return true;
        }
        if (isSkippedBlock(support)
                || (!support.isReplaceable()
                        && !support.isLiquid()
                        && !support.getType().isAir())) {
            return false;
        }
        final Material mat = TunnelMaterials.pickSupport(dominantRock, env, support.getY());
        support.setType(mat, false);
        return true;
    }

    private static void applyTurnStairEdits(
            final World world, final TunnelPlan.StepPlan plan, final Set<BlockPos> skipped) {
        for (final BlockPos pos : plan.removeBlocks()) {
            if (skipped.contains(pos)) {
                continue;
            }
            final Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (Tag.STAIRS.isTagged(block.getType())) {
                block.setType(Material.AIR, false);
            }
        }
        for (final TunnelPlan.StairRotation rot : plan.rotateStairs()) {
            if (skipped.contains(rot.pos())) {
                continue;
            }
            final Block block =
                    world.getBlockAt(rot.pos().x(), rot.pos().y(), rot.pos().z());
            if (block.getBlockData() instanceof final Stairs stairs) {
                stairs.setFacing(rot.newFacing());
                stairs.setHalf(Bisected.Half.TOP);
                block.setBlockData(stairs, true);
            }
        }
    }

    private static void placeStairs(
            final World world,
            final TunnelPlan.StepPlan plan,
            final @Nullable Material dominantRock,
            final World.Environment env,
            final Set<BlockPos> skipped) {
        for (final TunnelPlan.StairPlacement placement : plan.stairs()) {
            if (skipped.contains(placement.pos())) {
                continue;
            }
            final Block block = world.getBlockAt(
                    placement.pos().x(), placement.pos().y(), placement.pos().z());
            if (!block.getType().isAir()) {
                continue;
            }
            final Block sideWall = world.getBlockAt(
                    placement.sideWallProbe().x(),
                    placement.sideWallProbe().y(),
                    placement.sideWallProbe().z());
            final Block ceiling = world.getBlockAt(
                    placement.ceilingProbe().x(),
                    placement.ceilingProbe().y(),
                    placement.ceilingProbe().z());
            final Material stairMat = TunnelMaterials.pickStair(
                    sideWall.getType(),
                    ceiling.getType(),
                    dominantRock,
                    env,
                    placement.pos().y());
            final Stairs data = (Stairs) stairMat.createBlockData();
            data.setFacing(placement.facing());
            data.setHalf(Bisected.Half.TOP);
            block.setBlockData(data, true);
        }
    }

    private static void placeLanterns(final World world, final TunnelPlan.StepPlan plan, final Set<BlockPos> skipped) {
        for (final TunnelPlan.LanternPlacement lantern : plan.lanterns()) {
            if (skipped.contains(lantern.pos())) {
                continue;
            }
            final Block block = world.getBlockAt(
                    lantern.pos().x(), lantern.pos().y(), lantern.pos().z());
            if (!block.getType().isAir()) {
                continue;
            }
            final Block ceiling = world.getBlockAt(
                    lantern.ceilingPos().x(),
                    lantern.ceilingPos().y(),
                    lantern.ceilingPos().z());
            if (!ceiling.isSolid()) {
                continue;
            }
            final Lantern data = (Lantern) Material.LANTERN.createBlockData();
            data.setHanging(true);
            block.setBlockData(data, false);
        }
    }

    private static @Nullable Material dominantRock(final Map<Material, Integer> counts) {
        Material best = null;
        int bestCount = 0;
        for (final Map.Entry<Material, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    /** Returns whether {@code block} must be skipped (unbreakable, protected utility block, or inventory container). */
    static boolean isSkippedBlock(final Block block) {
        final Material type = block.getType();
        if (TunnelMaterials.isSkipped(type, false)) {
            return true;
        }
        return block.getState(false) instanceof InventoryHolder;
    }
}
