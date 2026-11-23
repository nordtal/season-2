package eu.nordtal.season.smp.duel;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Keeps kit items in a duel and grants no advancement criterion there. */
public final class DuelListener implements Listener {

    private final Duels duels;
    private final org.bukkit.plugin.Plugin plugin;

    public DuelListener(final org.bukkit.plugin.Plugin plugin, final Duels duels) {
        this.plugin = plugin;
        this.duels = duels;
    }

    /** {@code PlayerTeleportEvent} has its own handler list and may change worlds at the same coordinates. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(final org.bukkit.event.player.PlayerTeleportEvent event) {
        duels.moved(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX()
                && to.getBlockY() == from.getBlockY()
                && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        duels.moved(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        if (!duels.isInArena(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        // GraveListener checks the arena at HIGH before this handler.
        PaperScheduler.of(plugin).onMain(() -> duels.decide(player));
    }

    /** /kill, the void and {@code setHealth(0)} fire no damage event, so {@code onDeath} ends those duels. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(final org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof final Player player) || !duels.isInArena(player)) {
            return;
        }
        if (event.getFinalDamage() < player.getHealth()) {
            return;
        }
        event.setCancelled(true);
        PaperScheduler.of(plugin).onMain(() -> duels.decide(player));
    }

    /** A thrown item could leave the arena, whose restore removes only what lies inside it. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(final org.bukkit.event.player.PlayerDropItemEvent event) {
        if (duels.isInArena(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Keeps a fighter's screen limited to their own inventory. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof final Player player)) {
            return;
        }
        final boolean own = event.getInventory().equals(player.getInventory());
        if (DuelContainment.refusesInventory(duels.isInArena(player), own)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityHandOff(final PlayerInteractEntityEvent event) {
        refuseEntityHandOff(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityHandOff(final PlayerInteractAtEntityEvent event) {
        refuseEntityHandOff(event);
    }

    private void refuseEntityHandOff(final PlayerInteractEntityEvent event) {
        final Player player = event.getPlayer();
        if (DuelContainment.refusesEntityHandOff(duels.isInArena(player), hasHeldItem(player, event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        final boolean hasHeldItem = !event.getPlayerItem().getType().isAir();
        if (DuelContainment.refusesEntityHandOff(duels.isInArena(event.getPlayer()), hasHeldItem)) {
            event.setCancelled(true);
        }
    }

    private static boolean hasHeldItem(final Player player, final EquipmentSlot hand) {
        final ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        return !item.getType().isAir();
    }

    /** Refuses a fighter's use of a block that would take a kit item without opening an inventory. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockHandOff(final PlayerInteractEvent event) {
        final var block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        final ItemStack item = event.getItem();
        final boolean fights = duels.isInArena(event.getPlayer());
        final boolean hasHeldItem = item != null && !item.getType().isAir();
        if (DuelContainment.refusesBlockHandOff(fights, hasHeldItem, event.getAction(), block.getType())) {
            event.setCancelled(true);
        }
    }

    /** Refuses a fighter's spawn egg, also on water, whose click on air Bukkit fires as already cancelled. */
    @EventHandler
    public void onSpawnEgg(final PlayerInteractEvent event) {
        final ItemStack item = event.getItem();
        if (item == null || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        if (DuelContainment.refusesSpawnEgg(duels.isInArena(event.getPlayer()), event.getAction(), item.getType())) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(final EntityMountEvent event) {
        if (event.getEntity() instanceof final Player player && DuelContainment.refusesMount(duels.isInArena(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(final EntityPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null && duels.isInArena(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        final Player player = event.getPlayer();
        if (player != null && duels.isInArena(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        refuseBlockPlacement(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(final BlockMultiPlaceEvent event) {
        refuseBlockPlacement(event);
    }

    private void refuseBlockPlacement(final BlockPlaceEvent event) {
        if (duels.refusesPlacement(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(final ProjectileLaunchEvent event) {
        duels.launched(event.getEntity());
    }

    /** Refuses the grant itself, so a duel completes no advancement, shows no toast and counts for no gate later. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCriterion(final PlayerAdvancementCriterionGrantEvent event) {
        if (duels.refusesAdvancements(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        duels.joined(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(final org.bukkit.event.player.PlayerRespawnEvent event) {
        duels.respawned(event);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        duels.quit(event.getPlayer());
    }

    /** A chat line means the dialog is closed. The chat event runs off the main thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(final io.papermc.paper.event.player.AsyncChatEvent event) {
        final Player player = event.getPlayer();
        PaperScheduler.of(plugin).onMain(() -> duels.typed(player));
    }

    /** A command means the dialog is closed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(final org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        duels.typed(event.getPlayer());
    }
}
