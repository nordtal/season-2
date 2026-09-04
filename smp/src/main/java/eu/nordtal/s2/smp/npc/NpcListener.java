package eu.nordtal.s2.smp.npc;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Clicking the NPC, and everything that follows from it.
 *
 * <p>Three things live here because they are one conversation: opening the objective list, opening
 * a deposit screen from it, and the confirmation that is the only moment items change hands.
 *
 * <p><b>Closing a deposit screen always gives everything back.</b> A plugin inventory that is simply
 * closed drops its contents into nothing, so the handler below is not a courtesy - it is what stands
 * between somebody backing out of a screen and somebody losing a stack of diamonds.
 */
public final class NpcListener implements Listener {

    private final Plugin plugin;
    private final SmpDao dao;
    private final SpawnNpc npc;
    private final MilestoneTrack track;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public NpcListener(final Plugin plugin, final SmpDao dao, final SpawnNpc npc,
                       final MilestoneTrack track, final ObjectiveEngine engine,
                       final Identities identities, final Messages messages,
                       final PlayerLocales locales, final SmpSounds sounds) {
        this.plugin = plugin;
        this.dao = dao;
        this.npc = npc;
        this.track = track;
        this.engine = engine;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClickNpc(final PlayerInteractEntityEvent event) {
        if (!npc.is(event.getRightClicked().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        openObjectives(event.getPlayer());
    }

    private void openObjectives(final Player player) {
        final Locale locale = locales.of(player.getUniqueId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final Optional<String> activeKey = dao.activeMilestoneKey();
            if (activeKey.isEmpty()) {
                tell(player, MessageRenderer.of(messages).get(locale, "smp.objectives.none"));
                return;
            }
            final Milestone milestone = track.milestone(activeKey.get()).orElse(null);
            if (milestone == null) {
                tell(player, MessageRenderer.of(messages).get(locale, "smp.objectives.none"));
                return;
            }
            final List<ObjectiveRow> rows = dao.objectivesOf(activeKey.get());

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.openInventory(
                            new ObjectiveGui(messages, locale, milestone, rows).getInventory());
                }
            });
        });
    }

    @EventHandler
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final Object holder = event.getInventory().getHolder();

        if (holder instanceof ObjectiveGui gui) {
            // Nothing in the list is ever picked up.
            event.setCancelled(true);
            gui.at(event.getRawSlot()).ifPresent(entry -> {
                if (entry.isHandIn()) {
                    sounds.play(player, Feedback.SELECT);
                    player.openInventory(new HandInGui(messages,
                            locales.of(player.getUniqueId()), entry.objective(),
                            entry.row().amount(), entry.row().target()).getInventory());
                } else {
                    // A statistic counts itself and an advancement is earned elsewhere, which is
                    // what the item's own lore says. Clicking one is a click the server cannot do
                    // anything with, and silence there reads as a menu that is broken.
                    sounds.play(player, Feedback.REFUSED);
                }
            });
            return;
        }

        if (holder instanceof HandInGui gui) {
            // The deposit slots are deliberately free: this is a chest a player fills. Only the
            // confirm button and the frame around it are locked.
            if (event.getRawSlot() >= 0 && event.getRawSlot() < event.getInventory().getSize()
                    && !HandInGui.isDeposit(event.getRawSlot())) {
                event.setCancelled(true);
            }
            if (HandInGui.isConfirm(event.getRawSlot())) {
                confirm(player, gui);
            }
        }
    }

    /**
     * The one moment items change hands.
     *
     * <p>What is taken is decided by {@link HandIn}, which knows nothing about a server and is unit
     * tested; this only applies the answer and credits it. Everything not taken stays in the screen
     * and comes back when it closes.
     */
    private void confirm(final Player player, final HandInGui gui) {
        final Locale locale = locales.of(player.getUniqueId());
        final Optional<String> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(MessageRenderer.of(messages).get(locale, "smp.error.no-account-link"));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        final HandIn.Result result =
                HandIn.sort(gui.offered(), gui.wanted(), gui.stillNeeded());
        if (result.accepted() <= 0) {
            player.sendMessage(MessageRenderer.of(messages).get(locale, "smp.handin.nothing-wanted"));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        gui.apply(result);
        final String objectiveKey = gui.objective().key();
        final long accepted = result.accepted();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final long credited =
                    engine.credit(discordId.get(), objectiveKey, accepted, player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.handin.accepted",
                        "amount", credited));
                sounds.play(player, Feedback.SMALL_SUCCESS);
                player.closeInventory();
            });
        });
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof HandInGui gui
                && event.getPlayer() instanceof Player player) {
            gui.returnEverything(player);
        }
    }

    /** Sends one already-rendered message on the main thread, from wherever it is called. */
    private void tell(final Player player, final Component message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }
}
