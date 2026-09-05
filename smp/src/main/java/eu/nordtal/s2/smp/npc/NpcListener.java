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
    /**
     * The milestone track, <b>as a supplier</b>.
     *
     * <p>{@code /smp reload} replaces the plugin's track with a new instance - that is the whole
     * reason {@code milestones.yml} is a separate reloadable file, because a milestone is appended
     * and a target lowered mid-season. A reference captured at enable would go on reading the
     * definitions the server started with, for the rest of the season, and nothing would say so.</p>
     */
    private final java.util.function.Supplier<MilestoneTrack> track;
    private final ObjectiveEngine engine;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final SmpSounds sounds;

    public NpcListener(final Plugin plugin, final SmpDao dao, final SpawnNpc npc,
                       final java.util.function.Supplier<MilestoneTrack> track, final ObjectiveEngine engine,
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
                tell(player, MessageRenderer.of(messages).get(locale, "smp.objectives.none"),
                        Feedback.REFUSED);
                return;
            }
            final Milestone milestone = track.get().milestone(activeKey.get()).orElse(null);
            if (milestone == null) {
                tell(player, MessageRenderer.of(messages).get(locale, "smp.objectives.none"),
                        Feedback.REFUSED);
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

        // Taken now, on the click, so the player cannot pull them back out while the credit is in
        // flight - and held, because the credit can legitimately pay for none of them.
        final java.util.List<org.bukkit.inventory.ItemStack> taken = gui.apply(result);
        final String objectiveKey = gui.objective().key();
        final long accepted = result.accepted();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long credited;
            try {
                credited = engine.credit(discordId.get(), objectiveKey, accepted, player.getUniqueId());
            } catch (final RuntimeException failure) {
                // The database said no. Without this the items are gone and the player is told
                // nothing at all, because the callback below never runs.
                plugin.getLogger().severe("the hand-in for " + player.getName() + " on "
                        + objectiveKey + " could not be credited, giving the items back: "
                        + failure.getMessage());
                credited = 0;
            }
            final long paid = credited;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (paid <= 0) {
                    // Nothing was credited - the objective finished while this screen was open, or
                    // the write failed. The items were already taken, so they go straight back:
                    // returnEverything only knows about the slots, and these are no longer in them.
                    // A "handed in 0, thank you" line with the diamonds gone is the one outcome this
                    // screen must never produce.
                    if (!player.isOnline()) {
                        // The one case nothing here can fix: the items belong to a player who is no
                        // longer on the server, and this plugin has no mailbox. Named in the log,
                        // stack by stack, so an admin can hand them back - which is the whole
                        // difference between an incident and a silent loss. It is on the owner's
                        // rehearsal list whether this deserves a real store.
                        plugin.getLogger().severe(player.getName() + " left while a hand-in on "
                                + objectiveKey + " was in flight, it credited nothing, and these"
                                + " items could not be returned: " + describe(taken));
                        return;
                    }
                    gui.giveBack(player, taken);
                    player.sendMessage(MessageRenderer.of(messages)
                            .get(locale, "smp.handin.nothing-credited"));
                    sounds.play(player, Feedback.REFUSED);
                    player.closeInventory();
                    return;
                }
                if (!player.isOnline()) {
                    return;
                }
                player.sendMessage(MessageRenderer.of(messages).format(locale, "smp.handin.accepted",
                        "amount", paid));
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

    /** {@code 12x DIAMOND, 3x EMERALD} - for a log line an admin has to act on. */
    private static String describe(final java.util.List<org.bukkit.inventory.ItemStack> stacks) {
        return stacks.stream()
                .map(stack -> stack.getAmount() + "x " + stack.getType().name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Sends one already-rendered message on the main thread, from wherever it is called. */
    private void tell(final Player player, final Component message) {
        tell(player, message, null);
    }

    /**
     * The same, plus a sound.
     *
     * <p>Both in the one hop back to the main thread: a menu that does not open is a refusal the
     * player asked for, and the line and its sound belong to the same moment.
     */
    private void tell(final Player player, final Component message, final Feedback feedback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
                if (feedback != null) {
                    sounds.play(player, feedback);
                }
            }
        });
    }
}
