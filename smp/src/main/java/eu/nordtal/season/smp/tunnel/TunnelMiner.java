package eu.nordtal.season.smp.tunnel;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;

/** Drives Tunnel Miner overlays, dynamic Survival mining speed, lifecycle cancellation and 5x5 excavation. */
public final class TunnelMiner implements Listener {

    private static final double DEFAULT_REACH = 4.5;

    private final NamespacedKey speedModifierKey;
    private final DoubleSupplier timeFactor;
    private final TunnelProtections protections;
    private final TunnelExecutor executor;
    private final TunnelOverlayPackets overlays = new TunnelOverlayPackets();

    private final Map<UUID, TunnelFrame> activeTunnels = new HashMap<>();
    private final Map<UUID, TunnelFrame> lastStartCandidates = new HashMap<>();
    private final Map<UUID, TunnelOverlayPackets.Shown> shownOverlays = new HashMap<>();
    private final Map<UUID, Double> appliedMultipliers = new HashMap<>();

    public TunnelMiner(
            final Plugin plugin,
            final DoubleSupplier timeFactor,
            final TunnelProtections protections,
            final Consumer<Block> railChanged,
            final Predicate<Block> isStation,
            final BiPredicate<Block, Boolean> removeStation) {
        this.speedModifierKey = new NamespacedKey(plugin, "tunnel_miner_speed");
        this.timeFactor = Objects.requireNonNull(timeFactor, "timeFactor");
        this.protections = Objects.requireNonNull(protections, "protections");
        this.executor = new TunnelExecutor(
                Objects.requireNonNull(railChanged, "railChanged"),
                Objects.requireNonNull(isStation, "isStation"),
                Objects.requireNonNull(removeStation, "removeStation"));
    }

    /** Updates overlays and mining speed for every online player once per tick. */
    public void tick(final Collection<? extends Player> online) {
        for (final Player player : online) {
            tickPlayer(player);
        }
    }

    /** Clears all overlays and speed modifiers on plugin disable. */
    public void stop(final Collection<? extends Player> online) {
        for (final Player player : online) {
            cancelTunnel(player);
        }
        activeTunnels.clear();
        lastStartCandidates.clear();
        shownOverlays.clear();
        appliedMultipliers.clear();
    }

    private void tickPlayer(final Player player) {
        final UUID id = player.getUniqueId();
        final TunnelFrame active = activeTunnels.get(id);
        if (active != null && shouldCancel(player, active)) {
            cancelTunnel(player);
            return;
        }
        if (!canUseMode(player.getGameMode())
                || player.isSneaking()
                || !TunnelMinerItems.hasTunnelMiner(player.getInventory().getItemInMainHand())) {
            hideOverlay(player);
            clearSpeedModifier(player);
            return;
        }
        final @Nullable RayTraceResult hit = rayTrace(player);
        final @Nullable Block target = hit == null ? null : hit.getHitBlock();
        if (active != null
                && target != null
                && active.world().equals(target.getWorld().getName())
                && active.isFrontFace(posOf(target))) {
            lastStartCandidates.remove(id);
            tickActive(player, active, target);
        } else {
            tickStartPreview(player, hit);
        }
    }

    private void tickActive(final Player player, final TunnelFrame active, final Block target) {
        final World world = target.getWorld();
        final TunnelAvailability.ActiveFace face = TunnelAvailability.evaluateActiveFace(
                active, breakablePredicate(world), fp -> protections.check(player, world, fp, false));
        if (!face.anyAvailable()) {
            hideOverlay(player);
            clearSpeedModifier(player);
            return;
        }
        final @Nullable TunnelZone aimed = active.zoneAt(posOf(target));
        final @Nullable TunnelZone effectiveAim = aimed != null && face.isAvailable(aimed) ? aimed : null;
        showActiveOverlay(player, active, effectiveAim, face.disabledZones());
        if (effectiveAim == null || isSingleBlockOnly(target)) {
            clearSpeedModifier(player);
            return;
        }
        applySpeed(player, target, TunnelPlan.planZone(active, effectiveAim));
    }

    private void tickStartPreview(final Player player, final @Nullable RayTraceResult hit) {
        final @Nullable TunnelFrame candidate = startCandidate(player, hit);
        if (candidate == null) {
            lastStartCandidates.remove(player.getUniqueId());
            hideOverlay(player);
            clearSpeedModifier(player);
            return;
        }
        final World world = player.getWorld();
        final TunnelPlan.StepPlan plan = TunnelPlan.planStart(candidate);
        final TunnelAvailability.Status status = TunnelAvailability.evaluateStart(
                plan, breakablePredicate(world), fp -> protections.check(player, world, fp, false));
        if (!status.isAvailable()) {
            lastStartCandidates.remove(player.getUniqueId());
            hideOverlay(player);
            clearSpeedModifier(player);
            return;
        }
        lastStartCandidates.put(player.getUniqueId(), candidate);
        showStartOverlay(player, candidate);
        final Block target = Objects.requireNonNull(Objects.requireNonNull(hit).getHitBlock());
        if (isSingleBlockOnly(target)) {
            clearSpeedModifier(player);
        } else {
            applySpeed(player, target, plan);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        final Player player = event.getPlayer();
        if (!canUseMode(player.getGameMode()) || player.isSneaking()) {
            return;
        }
        final ItemStack tool = player.getInventory().getItemInMainHand();
        if (!TunnelMinerItems.hasTunnelMiner(tool)) {
            return;
        }
        final Block block = event.getBlock();
        if (isSingleBlockOnly(block)) {
            return;
        }
        final UUID id = player.getUniqueId();
        final World world = block.getWorld();
        final TunnelFrame active = activeTunnels.get(id);
        if (active != null && !active.world().equals(world.getName())) {
            cancelTunnel(player);
            return;
        }
        if (active != null && active.isFrontFace(posOf(block))) {
            final @Nullable TunnelZone zone = active.zoneAt(posOf(block));
            if (zone == null) {
                return;
            }
            final TunnelPlan.StepPlan plan = TunnelPlan.planZone(active, zone);
            final TunnelAvailability.Status status = TunnelAvailability.evaluateZone(
                    active, zone, plan, breakablePredicate(world), fp -> protections.check(player, world, fp, true));
            executeValidated(event, player, world, tool, active, zone, plan, status);
            return;
        }
        final @Nullable TunnelFrame candidate = startCandidateForBreak(player, block, rayTrace(player));
        if (candidate == null) {
            return;
        }
        final TunnelPlan.StepPlan plan = TunnelPlan.planStart(candidate);
        final TunnelAvailability.Status status = TunnelAvailability.evaluateStart(
                plan, breakablePredicate(world), fp -> protections.check(player, world, fp, true));
        executeValidated(event, player, world, tool, null, null, plan, status);
    }

    private void executeValidated(
            final BlockBreakEvent event,
            final Player player,
            final World world,
            final ItemStack tool,
            final @Nullable TunnelFrame previousFrame,
            final @Nullable TunnelZone zone,
            final TunnelPlan.StepPlan plan,
            final TunnelAvailability.Status status) {
        switch (TunnelAvailability.executionOutcome(status)) {
            case FALLBACK_SINGLE_BLOCK -> {
                return;
            }
            case CANCEL_PROTECTED -> {
                event.setCancelled(true);
                return;
            }
            case EXECUTE -> {
                event.setCancelled(true);
                executor.execute(player, world, tool, plan, previousFrame, zone);
                if (player.getGameMode() == GameMode.SURVIVAL) {
                    player.damageItemStack(EquipmentSlot.HAND, 1);
                }
                if (!TunnelMinerItems.hasTunnelMiner(player.getInventory().getItemInMainHand())) {
                    cancelTunnel(player);
                    return;
                }
                lastStartCandidates.remove(player.getUniqueId());
                activeTunnels.put(player.getUniqueId(), plan.nextFrame());
                clearSpeedModifier(player);
                hideOverlay(player);
                tickPlayer(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Player player = event.getPlayer();
        if (!player.isSneaking() || !activeTunnels.containsKey(player.getUniqueId())) {
            return;
        }
        if (TunnelMinerItems.hasTunnelMiner(player.getInventory().getItemInMainHand())) {
            cancelTunnel(player);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        clearSpeedModifier(player);
        activeTunnels.remove(player.getUniqueId());
        lastStartCandidates.remove(player.getUniqueId());
        shownOverlays.remove(player.getUniqueId());
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        cancelTunnel(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        cancelTunnel(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGameModeChange(final PlayerGameModeChangeEvent event) {
        if (!canUseMode(event.getNewGameMode())) {
            cancelTunnel(event.getPlayer());
        }
    }

    private void cancelTunnel(final Player player) {
        activeTunnels.remove(player.getUniqueId());
        lastStartCandidates.remove(player.getUniqueId());
        hideOverlay(player);
        clearSpeedModifier(player);
    }

    private boolean shouldCancel(final Player player, final TunnelFrame active) {
        if (player.isDead() || !canUseMode(player.getGameMode())) {
            return true;
        }
        final Location loc = Objects.requireNonNull(player.getLocation());
        if (!active.world().equals(loc.getWorld().getName())) {
            return true;
        }
        return !active.withinActiveRange(loc.getX(), loc.getY(), loc.getZ());
    }

    private @Nullable TunnelFrame startCandidate(final Player player, final @Nullable RayTraceResult hit) {
        if (hit == null) {
            return null;
        }
        final @Nullable Block block = hit.getHitBlock();
        final @Nullable BlockFace face = hit.getHitBlockFace();
        if (block == null || face == null) {
            return null;
        }
        return buildStartFrame(player, block, face);
    }

    private @Nullable TunnelFrame startCandidateForBreak(
            final Player player, final Block broken, final @Nullable RayTraceResult hit) {
        if (hit != null
                && hit.getHitBlock() != null
                && hit.getHitBlock().equals(broken)
                && hit.getHitBlockFace() != null) {
            return buildStartFrame(player, broken, hit.getHitBlockFace());
        }
        final @Nullable TunnelFrame preview = lastStartCandidates.get(player.getUniqueId());
        if (preview == null || !preview.world().equals(broken.getWorld().getName())) {
            return null;
        }
        return preview.isFrontFace(posOf(broken)) ? preview : null;
    }

    private @Nullable TunnelFrame buildStartFrame(final Player player, final Block target, final BlockFace face) {
        final @Nullable TunnelHeading outward = TunnelHeading.fromBlockFace(face);
        if (outward == null) {
            return null;
        }
        final TunnelHeading heading = outward.opposite();
        final Location loc = Objects.requireNonNull(player.getLocation());
        final @Nullable BlockPos origin = TunnelFrame.startOrigin(posOf(target), player.isOnGround(), loc.getY());
        if (origin == null) {
            return null;
        }
        return new TunnelFrame(target.getWorld().getName(), origin, heading, 0);
    }

    private static Predicate<BlockPos> breakablePredicate(final World world) {
        final Map<BlockPos, Boolean> cache = new HashMap<>();
        return pos -> cache.computeIfAbsent(pos, p -> isBreakable(world.getBlockAt(p.x(), p.y(), p.z())));
    }

    private static boolean isBreakable(final Block block) {
        return !block.getType().isAir()
                && block.isSolid()
                && !block.isLiquid()
                && !TunnelExecutor.isSkippedBlock(block);
    }

    private void applySpeed(final Player player, final Block target, final TunnelPlan.StepPlan plan) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            clearSpeedModifier(player);
            return;
        }
        final double currentMult = appliedMultipliers.getOrDefault(player.getUniqueId(), 1.0);
        final double targetBaseSpeed = target.getBreakSpeed(player) / currentMult;
        if (targetBaseSpeed <= 0.0 || !Double.isFinite(targetBaseSpeed)) {
            clearSpeedModifier(player);
            return;
        }
        final double targetTime = 1.0 / targetBaseSpeed;
        double totalTime = 0.0;
        final World world = target.getWorld();
        for (final BlockPos pos : plan.carveBlocks()) {
            final Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (b.getType().isAir() || b.isLiquid() || TunnelExecutor.isSkippedBlock(b)) {
                continue;
            }
            if (b.getType().getHardness() <= 0f) {
                continue;
            }
            final double speed = b.getBreakSpeed(player) / currentMult;
            if (speed > 0.0 && Double.isFinite(speed)) {
                totalTime += 1.0 / speed;
            }
        }
        final double mult = TunnelMaterials.speedMultiplier(targetTime, totalTime, timeFactor.getAsDouble());
        setSpeedModifier(player, mult);
    }

    private void setSpeedModifier(final Player player, final double multiplier) {
        final UUID id = player.getUniqueId();
        final @Nullable Double prev = appliedMultipliers.get(id);
        if (prev != null && Math.abs(prev - multiplier) < 1e-4) {
            return;
        }
        final @Nullable AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null) {
            return;
        }
        attr.removeModifier(speedModifierKey);
        if (multiplier < 0.9999) {
            attr.addTransientModifier(new AttributeModifier(
                    speedModifierKey, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            appliedMultipliers.put(id, multiplier);
        } else {
            appliedMultipliers.remove(id);
        }
    }

    private void clearSpeedModifier(final Player player) {
        if (appliedMultipliers.remove(player.getUniqueId()) == null) {
            return;
        }
        final @Nullable AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr != null) {
            attr.removeModifier(speedModifierKey);
        }
    }

    private void showStartOverlay(final Player player, final TunnelFrame frame) {
        final UUID id = player.getUniqueId();
        final TunnelOverlayPackets.@Nullable Shown current = shownOverlays.get(id);
        if (current != null) {
            if (current.sameStart(frame)) {
                return;
            }
            overlays.remove(id, current);
        }
        shownOverlays.put(id, overlays.spawnStart(id, frame));
    }

    private void showActiveOverlay(
            final Player player,
            final TunnelFrame frame,
            final @Nullable TunnelZone aimed,
            final Set<TunnelZone> disabled) {
        final UUID id = player.getUniqueId();
        final TunnelOverlayPackets.@Nullable Shown current = shownOverlays.get(id);
        if (current != null) {
            if (current.sameActive(frame)) {
                overlays.updateActive(id, current, aimed, disabled);
                return;
            }
            overlays.remove(id, current);
        }
        shownOverlays.put(id, overlays.spawnActive(id, frame, aimed, disabled));
    }

    private void hideOverlay(final Player player) {
        final TunnelOverlayPackets.@Nullable Shown current = shownOverlays.remove(player.getUniqueId());
        if (current != null) {
            overlays.remove(player.getUniqueId(), current);
        }
    }

    private static @Nullable RayTraceResult rayTrace(final Player player) {
        final @Nullable AttributeInstance reachAttr = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        final double reach = reachAttr != null ? reachAttr.getValue() : DEFAULT_REACH;
        return player.rayTraceBlocks(reach, FluidCollisionMode.NEVER);
    }

    private static boolean canUseMode(final GameMode mode) {
        return mode == GameMode.SURVIVAL || mode == GameMode.CREATIVE;
    }

    private static boolean isSingleBlockOnly(final Block block) {
        if (TunnelExecutor.isSkippedBlock(block)) {
            return true;
        }
        return !block.isSolid() && block.getType().getHardness() == 0f;
    }

    private static BlockPos posOf(final Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }
}
