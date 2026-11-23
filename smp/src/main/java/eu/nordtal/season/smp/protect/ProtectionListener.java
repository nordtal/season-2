package eu.nordtal.season.smp.protect;

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
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jspecify.annotations.Nullable;

/**
 * Protects the configured 3D spawn areas against building, damage, hostile spawns and mob griefing.
 * Admins in Creative build, and active duel fighters inside the arena are exempt from block and damage rules.
 */
public final class ProtectionListener implements Listener {

    /** On the action bar rather than in chat: it fires on a misclick as often as on an attempt. */
    static final DocumentSpec<DocumentSpec.None> DENIED = DocumentSpec.game(
                    "smp", "protect-denied", "Spawn", DocumentSpec.Shape.ACTION_BAR)
            .titled("Spawn protected");

    private final Supplier<PolygonAreas> regions;
    private final Arenas arenas;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public ProtectionListener(
            final Supplier<PolygonAreas> regions,
            final Arenas arenas,
            final Identities identities,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.regions = regions;
        this.arenas = arenas;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
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

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Container)) {
            return;
        }
        if (deny(event.getPlayer(), block)) {
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
        if (inside(Objects.requireNonNull(event.getBlock().getLocation()))
                && !arenas.isActiveArenaBlock(event.getBlock())) {
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
        if (inside(Objects.requireNonNull(event.getToBlock().getLocation()))
                && !arenas.isActiveArenaBlock(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        event.blockList()
                .removeIf(block ->
                        inside(Objects.requireNonNull(block.getLocation())) && !arenas.isActiveArenaBlock(block));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        event.blockList()
                .removeIf(block ->
                        inside(Objects.requireNonNull(block.getLocation())) && !arenas.isActiveArenaBlock(block));
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
        if (blocksHostileSpawn(
                inside(Objects.requireNonNull(event.getLocation())), event.getEntity() instanceof Enemy)) {
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
    public void onMobTrample(final EntityInteractEvent event) {
        final Entity entity = event.getEntity();
        if (!(entity instanceof Player)
                && event.getBlock().getType() == Material.FARMLAND
                && inside(Objects.requireNonNull(event.getBlock().getLocation()))) {
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

    static boolean blocksHostileSpawn(final boolean insideSpawn, final boolean hostile) {
        return insideSpawn && hostile;
    }

    static boolean blocksMobGrief(final boolean insideSpawn, final boolean mob) {
        return insideSpawn && mob;
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
        player.sendActionBar(
                Lines.joined(renderer.format(identities.languageOf(player.getUniqueId()), Sent.of(DENIED))));
        sounds.play(player, Feedback.REFUSED);
        return true;
    }
}
