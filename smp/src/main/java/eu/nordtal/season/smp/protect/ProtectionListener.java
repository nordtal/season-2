package eu.nordtal.season.smp.protect;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.port.Arenas;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Protects the configured 3D spawn areas against building, interaction, griefing paths and stray mobs.
 * Admins in Creative, active duel fighters inside the arena and configured spawn exceptions stay exempt.
 */
public final class ProtectionListener implements Listener {

    /** On the action bar rather than in chat: it fires on a misclick as often as on an attempt. */
    public static final DocumentSpec<DocumentSpec.None> DENIED = DocumentSpec.game(
                    "smp", "protect-denied", "Spawn", DocumentSpec.Shape.ACTION_BAR)
            .titled("Spawn protected");

    private final Supplier<PolygonAreas> regions;
    private final Arenas arenas;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;
    private final Predicate<Block> isWheelBlock;
    private final Predicate<Block> isStationRail;
    private final Predicate<Block> isNetworkRail;
    private final Predicate<Block> isAllowedException;

    public ProtectionListener(
            final Supplier<PolygonAreas> regions,
            final Arenas arenas,
            final Identities identities,
            final MessageRenderer renderer,
            final SmpSounds sounds,
            final Predicate<Block> isWheelBlock,
            final Predicate<Block> isStationRail,
            final Predicate<Block> isNetworkRail,
            final Predicate<Block> isAllowedException) {
        this.regions = regions;
        this.arenas = arenas;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        this.isWheelBlock = isWheelBlock;
        this.isStationRail = isStationRail;
        this.isNetworkRail = isNetworkRail;
        this.isAllowedException = isAllowedException;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(final BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(final BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        final Player player = event.getPlayer();
        final @Nullable Block block = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL) {
            if (block != null
                    && inside(block.getLocation())
                    && !builds(player)
                    && !arenas.buildsInArena(player, block)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (handleDisruptiveItem(event, player, block)) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY
                || block == null
                || !inside(block.getLocation())
                || builds(player)
                || arenas.buildsInArena(player, block)
                || isExemptBlock(block)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        if (block.getType().isInteractable() && !player.isSneaking()) {
            refuse(player);
        }
    }

    private boolean handleDisruptiveItem(
            final PlayerInteractEvent event, final Player player, final @Nullable Block block) {
        if (event.useItemInHand() == Event.Result.DENY) {
            return false;
        }
        final @Nullable ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        final Location at = Objects.requireNonNull(block != null ? block.getLocation() : player.getLocation());
        final boolean inDuelArena = block != null ? arenas.buildsInArena(player, block) : arenas.isInArena(player);
        if (!inside(at) || builds(player) || inDuelArena) {
            return false;
        }
        final boolean onNetworkRail = block != null && isNetworkRail.test(block);
        final boolean checksBlockTools = block != null && !isExemptBlock(block);
        if (!SpawnProtectionRules.isDisruptiveItem(item.getType(), checksBlockTools, onNetworkRail)) {
            return false;
        }
        event.setUseItemInHand(Event.Result.DENY);
        if (block != null && !isExemptBlock(block)) {
            event.setUseInteractedBlock(Event.Result.DENY);
        }
        refuse(player);
        return true;
    }

    private boolean isExemptBlock(final Block block) {
        final Material type = block.getType();
        return SpawnProtectionRules.isCushion(type)
                || SpawnProtectionRules.isLocalUtilityBlock(type)
                || isWheelBlock.test(block)
                || isStationRail.test(block)
                || isNetworkRail.test(block)
                || isAllowedException.test(block);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (denyDecorativeEntity(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
        if (denyDecorativeEntity(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onArmorStandManipulate(final PlayerArmorStandManipulateEvent event) {
        if (denyDecorativeEntity(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    private boolean denyDecorativeEntity(final Player player, final Entity entity) {
        if (!(entity instanceof ItemFrame) && !(entity instanceof ArmorStand)) {
            return false;
        }
        if (!inside(Objects.requireNonNull(entity.getLocation())) || builds(player)) {
            return false;
        }
        refuse(player);
        return true;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onEntityPlace(final EntityPlaceEvent event) {
        final @Nullable Player player = event.getPlayer();
        final Block block = event.getBlock();
        final boolean inSpawn =
                inside(block.getLocation()) || inside(event.getEntity().getLocation());
        if (!inSpawn || (player != null && (builds(player) || arenas.buildsInArena(player, block)))) {
            return;
        }
        if (event.getEntity() instanceof RideableMinecart && isNetworkRail.test(block)) {
            return;
        }
        event.setCancelled(true);
        if (player != null) {
            refuse(player);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPotionSplash(final PotionSplashEvent event) {
        final Location impact = event.getEntity().getLocation();
        if (inside(impact) && !arenas.isActiveArenaBlock(impact.getBlock())) {
            event.setCancelled(true);
            return;
        }
        for (final LivingEntity affected : event.getAffectedEntities()) {
            if (inside(affected.getLocation())
                    && !(affected instanceof final Player player && arenas.isInArena(player))) {
                event.setIntensity(affected, 0.0);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onAreaEffectCloudApply(final AreaEffectCloudApplyEvent event) {
        event.getAffectedEntities()
                .removeIf(entity -> inside(entity.getLocation())
                        && !(entity instanceof final Player player && arenas.isInArena(player)));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onProjectileHit(final ProjectileHitEvent event) {
        if (event.getEntity() instanceof WindCharge
                && inside(event.getEntity().getLocation())
                && !arenas.isActiveArenaBlock(event.getEntity().getLocation().getBlock())) {
            event.setCancelled(true);
            return;
        }
        final @Nullable Block hit = event.getHitBlock();
        if (hit != null
                && inside(hit.getLocation())
                && !arenas.isActiveArenaBlock(hit)
                && SpawnProtectionRules.isProjectileTriggerableBlock(hit.getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        final BlockFace direction = event.getDirection();
        if (isProtectedNonArena(event.getBlock().getRelative(direction))) {
            event.setCancelled(true);
            return;
        }
        for (final Block moved : event.getBlocks()) {
            if (isProtectedNonArena(moved) || isProtectedNonArena(moved.getRelative(direction))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        final BlockFace direction = event.getDirection();
        for (final Block moved : event.getBlocks()) {
            if (isProtectedNonArena(moved) || isProtectedNonArena(moved.getRelative(direction))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDispense(final BlockDispenseEvent event) {
        final Block dispenser = event.getBlock();
        if (isProtectedNonArena(dispenser)) {
            event.setCancelled(true);
            return;
        }
        if (dispenser.getBlockData() instanceof final Directional directional
                && isProtectedNonArena(dispenser.getRelative(directional.getFacing()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onStructureGrow(final StructureGrowEvent event) {
        final @Nullable Player player = event.getPlayer();
        if (player != null && builds(player)) {
            return;
        }
        for (final BlockState state : event.getBlocks()) {
            if (isProtectedNonArena(state.getBlock())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onEntityBlockForm(final EntityBlockFormEvent event) {
        if (isProtectedNonArena(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingPlace(final HangingPlaceEvent event) {
        if (event.getPlayer() != null && deny(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final Location at = Objects.requireNonNull(event.getEntity().getLocation());
        if (!inside(at)) {
            return;
        }
        if (event.getRemover() instanceof Player player && builds(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreakEnvironment(final HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent) {
            return;
        }
        if (inside(Objects.requireNonNull(event.getEntity().getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (isProtectedNonArena(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        if (!inside(Objects.requireNonNull(event.getBlock().getLocation()))) {
            return;
        }
        final Player player = event.getPlayer();
        if (player != null && (builds(player) || arenas.buildsInArena(player, event.getBlock()))) {
            return;
        }
        if (arenas.isActiveArenaBlock(event.getBlock())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        // Only the target block counts. A source block inside a protected area stays.
        if (isProtectedNonArena(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedNonArena);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedNonArena);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDamage(final EntityDamageEvent event) {
        final Entity victim = event.getEntity();
        final @Nullable Entity damager =
                event instanceof final EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        final boolean inSpawn = inside(Objects.requireNonNull(victim.getLocation()));
        final boolean duelAllowed = victim instanceof final Player player && arenas.allowsDuelDamage(player, damager);
        final boolean creativeDecorative = (victim instanceof ArmorStand || victim instanceof ItemFrame)
                && damager instanceof final Player attacker
                && builds(attacker);
        if (blocksDamage(inSpawn, isProtectedFromDamage(victim), duelAllowed, creativeDecorative)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onCreatureSpawn(final CreatureSpawnEvent event) {
        if (SpawnProtectionRules.blocksCreatureSpawn(
                inside(Objects.requireNonNull(event.getLocation())), event.getSpawnReason())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onMobPathfind(final EntityPathfindEvent event) {
        if (!(event.getEntity() instanceof final Mob mob)) {
            return;
        }
        final boolean entityInSpawn = inside(mob.getLocation());
        final boolean targetInSpawn = inside(event.getLoc());
        final boolean tamed = mob instanceof final Tameable tameable && tameable.isTamed();
        final boolean luredByPlayer =
                mob instanceof Animals && mob.getTarget() == null && event.getTargetEntity() instanceof Player;
        if (SpawnProtectionRules.blocksMobPathfind(
                entityInSpawn, targetInSpawn, mob.isLeashed(), mob.isInsideVehicle(), tamed, luredByPlayer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onMobTarget(final EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof final Mob mob) || !(event.getTarget() instanceof final Player target)) {
            return;
        }
        final boolean tamed = mob instanceof final Tameable tameable && tameable.isTamed();
        if (SpawnProtectionRules.blocksMobTarget(
                inside(Objects.requireNonNull(target.getLocation())), tamed, event.getReason())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onMobGrief(final EntityChangeBlockEvent event) {
        final Entity entity = event.getEntity();
        final boolean mob = entity instanceof LivingEntity && !(entity instanceof Player);
        if (blocksMobGrief(inside(Objects.requireNonNull(event.getBlock().getLocation())), mob)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onEntityInteract(final EntityInteractEvent event) {
        if (!(event.getEntity() instanceof Player) && isProtectedNonArena(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    static boolean isProtectedFromDamage(final Entity entity) {
        return entity instanceof Player
                || entity instanceof Animals
                || entity instanceof NPC
                || entity instanceof Mannequin
                || entity instanceof ArmorStand
                || entity instanceof ItemFrame;
    }

    static boolean blocksDamage(
            final boolean insideSpawn,
            final boolean protectedEntity,
            final boolean duelAllowed,
            final boolean creativeDecorative) {
        return insideSpawn && protectedEntity && !duelAllowed && !creativeDecorative;
    }

    static boolean blocksMobGrief(final boolean insideSpawn, final boolean mob) {
        return insideSpawn && mob;
    }

    private boolean isProtectedNonArena(final Block block) {
        return inside(Objects.requireNonNull(block.getLocation())) && !arenas.isActiveArenaBlock(block);
    }

    private boolean inside(final Location location) {
        return regions.get()
                .containsBlock(
                        location.getWorld().getName(),
                        location.getBlockX(),
                        location.getBlockY(),
                        location.getBlockZ());
    }

    private boolean builds(final Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                && identities.of(player.getUniqueId()).admin();
    }

    /** A duel's fighter builds in the arena, which is restored after the duel. */
    private boolean deny(final Player player, final Block block) {
        if (!inside(Objects.requireNonNull(block.getLocation()))
                || builds(player)
                || arenas.buildsInArena(player, block)) {
            return false;
        }
        refuse(player);
        return true;
    }

    private void refuse(final Player player) {
        player.sendActionBar(
                Lines.joined(renderer.format(identities.languageOf(player.getUniqueId()), Sent.of(DENIED))));
        sounds.play(player, Feedback.REFUSED);
    }
}
