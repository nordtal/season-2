package eu.nordtal.season.smp.npc;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.SmpTexts;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.port.Contributions;
import eu.nordtal.season.smp.state.SeasonState;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.plugin.Plugin;

/**
 * Opens the objective dialog when a player clicks the NPC and credits the items of a confirmed hand-in.
 *
 * The credit runs off the main thread. Its result is applied on the main thread.
 */
public final class NpcListener implements Listener {

    private final Plugin plugin;
    private final SpawnNpc npc;
    private final SeasonState season;
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final Contributions contributions;
    private final Identities identities;

    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Dialogs dialogs;
    private final SmpSounds sounds;

    public NpcListener(
            final Plugin plugin,
            final SpawnNpc npc,
            final SeasonState season,
            final java.util.function.Supplier<MilestoneTrack> track,
            final Contributions contributions,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.npc = npc;
        this.season = season;
        this.track = track;
        this.contributions = contributions;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.dialogs = dialogs;
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
        final Locale locale = identities.languageOf(player.getUniqueId());
        final SeasonState.Active active = season.active();
        final String activeKey = active.key();
        final Milestone milestone =
                activeKey == null ? null : track.get().milestone(activeKey).orElse(null);
        if (milestone == null) {
            final MilestoneTrack shown = track.get();
            final boolean all =
                    shown.size() > 0 && shown.next(season.completedKeys()).isEmpty();
            player.showDialog(ObjectiveDialog.empty(renderer, documents, locale, all));
            return;
        }
        show(player, locale, milestone, active.objectives());
    }

    /** Reads the player's share from the database off the main thread, then opens the dialog on the main thread. */
    private void show(
            final Player player, final Locale locale, final Milestone milestone, final List<ObjectiveRow> rows) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        PaperScheduler.of(plugin).execute(() -> {
            final OwnShare.Summary share = OwnShare.of(discordId
                    .map(id -> contributions.ownContributions(milestone.key(), id))
                    .orElse(List.of()));

            PaperScheduler.of(plugin).onMain(() -> {
                if (player.isOnline()) {
                    new ObjectiveDialog(renderer, documents, dialogs, locale, milestone, rows, share, this::confirm)
                            .show(player, 0);
                }
            });
        });
    }

    /** Takes the items that {@link HandIn} accepts and credits them. Refuses a player without a linked account. */
    private void confirm(final Player player, final HandInGui gui) {
        final Locale locale = identities.languageOf(player.getUniqueId());
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(renderer.format(locale, Sent.of(SmpTexts.NO_ACCOUNT_LINK)));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        final HandIn.Result result = HandIn.sort(gui.offered(), gui.wanted(), gui.stillNeeded());
        if (result.accepted() <= 0) {
            sounds.play(player, Feedback.LOSS);
            gui.refuse(player);
            return;
        }

        // The items are kept until the credit result is known, because the credit may be 0.
        final java.util.List<org.bukkit.inventory.ItemStack> taken = gui.apply(result);
        gui.crediting();
        final String objectiveKey = gui.objective().key();
        final long accepted = result.accepted();

        PaperScheduler.of(plugin).execute(() -> {
            // Negative when the credit failed, which tells the window nothing about the objective.
            long credited;
            try {
                credited = contributions.credit(discordId.get(), objectiveKey, accepted, player.getUniqueId());
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .severe("the hand-in for " + player.getName() + " on "
                                + objectiveKey + " could not be credited, giving the items back: "
                                + failure.getMessage());
                credited = -1;
            }
            final long paid = credited;
            PaperScheduler.of(plugin).onMain(() -> applyCreditResult(player, gui, taken, objectiveKey, accepted, paid));
        });
    }

    /**
     * Shows the credit result in the window and puts back the taken items that were not counted. Main thread only.
     * Less counted than taken means the objective finished while the screen was open; a negative {@code paid} means
     * the credit failed, and the window stays as it was.
     */
    private void applyCreditResult(
            final Player player,
            final HandInGui gui,
            final java.util.List<org.bukkit.inventory.ItemStack> taken,
            final String objectiveKey,
            final long accepted,
            final long paid) {
        if (!player.isOnline()) {
            if (paid < accepted) {
                // An offline player cannot receive the items.
                plugin.getLogger()
                        .severe(player.getName() + " left while a hand-in on "
                                + objectiveKey + " was in flight; it credited " + Math.max(0L, paid) + " of "
                                + accepted + ", and the rest of these items could not be returned: "
                                + describe(taken));
            }
            return;
        }
        if (paid < 0) {
            gui.giveBack(player, taken);
            sounds.play(player, Feedback.LOSS);
            gui.uncredited();
            return;
        }
        gui.putBack(player, taken, accepted - paid);
        sounds.play(player, paid == 0 ? Feedback.LOSS : Feedback.SMALL_SUCCESS);
        gui.credited(player, paid, accepted);
    }

    private static String describe(final java.util.List<org.bukkit.inventory.ItemStack> stacks) {
        return stacks.stream()
                .map(stack -> stack.getAmount() + "x " + stack.getType().name())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
