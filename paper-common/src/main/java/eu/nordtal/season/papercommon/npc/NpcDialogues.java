package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcStore;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.papercommon.menu.Menus;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * The NPCs of one server and the dialogues players have with them, called on the main thread only.
 * A right-click on a figure starts one, or opens the menu the player skips to. It places the figures, reads the
 * inputs and runs every session each tick.
 */
public final class NpcDialogues implements Listener {

    private record PendingNotice(Locale locale, String npcName) {}

    private final Plugin plugin;
    private final Figures figures;
    private final Skips skips;
    private final Hud hud;
    private final Function<UUID, Locale> languageOf;
    private final Function<Locale, Labels> labels;
    private final BiFunction<Locale, String, Component> skipNotice;
    private final NpcMenus menus;
    private final Supplier<Feel> feel;
    private final DialogueSounds sounds = new DialogueSounds();
    private final DialoguePacketGuard packetGuard;
    private final NamespacedKey zoomKey;
    private final Map<UUID, DialogueSession> sessions = new HashMap<>();
    private final Map<UUID, Mannequin> previews = new HashMap<>();
    private final Map<UUID, PendingNotice> pendingNotices = new HashMap<>();
    private ViewPackets.@Nullable MaskItems maskItems;
    private Scheduler.@Nullable Task task;

    /** The parts a server hands in; {@code labels} gives the words of each language, {@code feel} the settings. */
    public record Parts(
            Hud hud,
            Function<UUID, Locale> languageOf,
            Function<Locale, Labels> labels,
            BiFunction<Locale, String, Component> skipNotice,
            NpcMenus menus,
            NamePlates plates,
            Supplier<Feel> feel) {}

    public NpcDialogues(final Plugin plugin, final NpcStore store, final Parts parts) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.skips = new Skips(plugin, store);
        this.figures = new Figures(plugin, parts.plates());
        this.hud = parts.hud();
        this.languageOf = parts.languageOf();
        this.labels = parts.labels();
        this.skipNotice = parts.skipNotice();
        this.menus = parts.menus();
        this.feel = parts.feel();
        this.packetGuard = new DialoguePacketGuard(this::scheduleResync, this::scheduleConfirm);
        this.zoomKey = new NamespacedKey(plugin, "npc_zoom");
    }

    /** Registers the listeners and starts the clock. */
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvents(figures, plugin);
        plugin.getServer().getPluginManager().registerEvents(new FigureGuard(figures), plugin);
        plugin.getServer().getPluginManager().registerEvents(skips, plugin);
        packetGuard.register();
        skips.start();
        task = PaperScheduler.of(plugin).onMainEvery(PaperScheduler.TICK, PaperScheduler.TICK, this::tick);
    }

    /** Ends every dialogue at once and removes the figures, as the plugin stops. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        List.copyOf(sessions.keySet()).forEach(this::end);
        pendingNotices.clear();
        packetGuard.stop();
        figures.removeAll();
    }

    /** Places the figures of {@code npcs}; a dialogue with an NPC that changed or went ends at once. */
    public void apply(final List<Npc> npcs) {
        final Set<UUID> changed = figures.apply(npcs);
        for (final DialogueSession session : List.copyOf(sessions.values())) {
            if (changed.contains(session.npc().id())
                    && !previews.containsKey(session.player().getUniqueId())) {
                end(session.player().getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final boolean figure = figures.is(event.getRightClicked());
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (figure || session != null) {
            event.setCancelled(true);
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (session != null) {
            session.run().confirm();
        } else if (figure) {
            figures.npcOf(event.getRightClicked()).ifPresent(npc -> begin(event.getPlayer(), npc));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        switch (Click.of(event.getAction())) {
            case RIGHT, LEFT -> {
                event.setCancelled(true);
                session.run().confirm();
            }
            case NONE -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(final PrePlayerAttackEntityEvent event) {
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            session.run().confirm();
        } else if (figures.is(event.getAttacked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHeld(final PlayerItemHeldEvent event) {
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.movingSlot()) {
            return;
        }
        if (session.run().select(event.getNewSlot())) {
            event.setCancelled(true);
        } else {
            session.hideHandsNow(event.getNewSlot());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(final InventoryClickEvent event) {
        final DialogueSession session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            session.resyncVisuals();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryDrag(final InventoryDragEvent event) {
        final DialogueSession session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            session.resyncVisuals();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(final PlayerDropItemEvent event) {
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            session.resyncVisuals();
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwapHand(final PlayerSwapHandItemsEvent event) {
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            session.resyncVisuals();
        }
    }

    private void scheduleResync(final UUID player) {
        if (!plugin.isEnabled()) {
            return;
        }
        PaperScheduler.of(plugin).onMain(() -> {
            final DialogueSession session = sessions.get(player);
            if (session != null) {
                session.resyncVisuals();
            }
        });
    }

    private void scheduleConfirm(final UUID player) {
        if (!plugin.isEnabled()) {
            return;
        }
        PaperScheduler.of(plugin).onMain(() -> {
            final DialogueSession session = sessions.get(player);
            if (session != null) {
                session.run().confirm();
            }
        });
    }

    private ViewPackets.MaskItems maskItems() {
        if (maskItems == null) {
            maskItems = ViewPackets.maskItems();
        }
        return maskItems;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(final InventoryCloseEvent event) {
        if (Menus.isMenuClose(event)) {
            sendPendingNotice(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onSneak(final PlayerToggleSneakEvent event) {
        final DialogueSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.run().sneak(event.isSneaking());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID player = event.getPlayer().getUniqueId();
        pendingNotices.remove(player);
        end(player);
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        end(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(final PlayerChangedWorldEvent event) {
        end(event.getPlayer().getUniqueId());
    }

    /** A teleport ends the dialogue; one of cause UNKNOWN is the server setting the view, as the camera does not. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            end(event.getPlayer().getUniqueId());
        }
    }

    private void begin(final Player player, final Npc npc) {
        final Mannequin figure = figures.figure(npc.id());
        if (figure == null || !DialogueRun.playable(npc.dialogue())) {
            return;
        }
        pendingNotices.remove(player.getUniqueId());
        final String skipped = skips.of(player.getUniqueId(), npc.id());
        final SkipChoice.Start start = SkipChoice.start(skipped, npc.dialogue(), player.isSneaking());
        if (start == SkipChoice.Start.OPEN_MENU) {
            open(player, Objects.requireNonNull(skipped, "skipped"), () -> {});
            return;
        }
        final Locale locale = languageOf.apply(player.getUniqueId());
        final Feel now = feel.get();
        final DialogueRun run = new DialogueRun(
                npc.dialogue(),
                NpcLooks.speaker(npc, locale),
                locale,
                now,
                labels.apply(locale),
                skipped,
                start == SkipChoice.Start.REPLAY,
                player.isSneaking());
        packetGuard.activate(player.getUniqueId(), player.getInventory().getHeldItemSlot(), maskItems());
        sessions.put(
                player.getUniqueId(),
                new DialogueSession(player, npc, figure, run, now, hud, sounds, packetGuard, zoomKey));
    }

    /**
     * Plays an unsaved NPC draft for {@code player} on a figure only it sees.
     * The live NPC stays as it is, and a hand-off opens its menu but keeps no skip. Returns false for no play.
     */
    public boolean preview(final Player player, final Npc npc, final Locale locale) {
        if (!DialogueRun.playable(npc.dialogue())) {
            return false;
        }
        end(player.getUniqueId());
        final Mannequin figure = figures.spawnPreview(npc, player);
        final Feel now = feel.get();
        final DialogueRun run = new DialogueRun(
                npc.dialogue(),
                NpcLooks.speaker(npc, locale),
                locale,
                now,
                labels.apply(locale),
                null,
                false,
                player.isSneaking());
        previews.put(player.getUniqueId(), figure);
        packetGuard.activate(player.getUniqueId(), player.getInventory().getHeldItemSlot(), maskItems());
        sessions.put(
                player.getUniqueId(),
                new DialogueSession(player, npc, figure, run, now, hud, sounds, packetGuard, zoomKey));
        return true;
    }

    private void tick() {
        figures.turnHeads(this::talker);
        for (final DialogueSession session : List.copyOf(sessions.values())) {
            final UUID player = session.player().getUniqueId();
            final Mannequin preview = previews.get(player);
            final Mannequin figure =
                    preview != null ? preview : figures.figure(session.npc().id());
            if (figure == null || !figure.isValid() || !session.player().isOnline()) {
                end(session.player().getUniqueId());
                continue;
            }
            for (final Effect effect : session.tick(figure)) {
                if (effect instanceof final Effect.HandOff handOff) {
                    if (preview == null) {
                        skips.apply(player, session.npc().id(), handOff.menu(), handOff.change());
                        if (handOff.change() == SkipChoice.Change.SKIP) {
                            final Locale locale = languageOf.apply(player);
                            pendingNotices.put(
                                    player,
                                    new PendingNotice(
                                            locale, session.npc().name().in(locale)));
                        }
                    }
                    end(player);
                    open(session.player(), handOff.menu(), () -> sendPendingNotice(player));
                } else if (effect instanceof Effect.End) {
                    end(session.player().getUniqueId());
                }
            }
        }
    }

    private @Nullable Player talker(final UUID npc) {
        for (final DialogueSession session : sessions.values()) {
            if (session.npc().id().equals(npc)) {
                return session.player();
            }
        }
        return null;
    }

    private void open(final Player player, final String key, final Runnable onClose) {
        menus.menu(key).ifPresentOrElse(menu -> menu.open().accept(player, onClose), () -> {
            pendingNotices.remove(player.getUniqueId());
            plugin.getLogger().warning("npc menu unknown, nothing opened: menu=" + key + " player=" + player.getName());
        });
    }

    private void sendPendingNotice(final UUID playerId) {
        final PendingNotice notice = pendingNotices.remove(playerId);
        if (notice == null) {
            return;
        }
        final Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.sendMessage(skipNotice.apply(notice.locale(), notice.npcName()));
        }
    }

    private void end(final UUID player) {
        packetGuard.releaseInventory(player);
        final DialogueSession session = sessions.remove(player);
        if (session != null) {
            session.stopNow();
        }
        final Mannequin preview = previews.remove(player);
        if (preview != null) {
            figures.removePreview(preview);
        }
        final Player online = Bukkit.getPlayer(player);
        packetGuard.deactivateAndFlush(player, packet -> {
            if (online != null && online.isOnline()) {
                ViewPackets.sendSilently(online, packet);
            }
        });
    }
}
