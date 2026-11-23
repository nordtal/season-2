package eu.nordtal.season.smp.npc;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.chat.Previews;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The NPC's {@code objectives} menu, which its dialogue hands off to.
 * It opens the objective dialog and credits the items of a confirmed hand-in. Unpaid returns for a player who went
 * offline are stored in the pending-items store and handed out at their next join.
 */
public final class ObjectivesMenu {

    /** The menu's key, which a dialogue's last step names to hand off to it. */
    public static final String MENU = "objectives";

    private final Plugin plugin;
    private final SeasonState season;
    private final Supplier<MilestoneTrack> track;

    private final Contributions contributions;
    private final Identities identities;

    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
    private final Dialogs dialogs;
    private final SmpSounds sounds;
    private final BiConsumer<DiscordId, ItemStack> storePending;

    public ObjectivesMenu(
            final Plugin plugin,
            final SeasonState season,
            final Supplier<MilestoneTrack> track,
            final Contributions contributions,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final SmpSounds sounds,
            final BiConsumer<DiscordId, ItemStack> storePending) {
        this.plugin = plugin;
        this.season = season;
        this.track = track;
        this.contributions = contributions;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
        this.dialogs = dialogs;
        this.sounds = sounds;
        this.storePending = storePending;
    }

    /** Hands the draft preview read-only examples of the objectives dialog and the hand-in chest. */
    public void examples(final BiConsumer<DocumentSpec<?>, Previews.Example> example) {
        example.accept(
                ObjectiveDialog.DOCUMENT,
                (reader, drafted) ->
                        ObjectiveDialog.example(drafted, dialogs, identities.languageOf(reader.getUniqueId()), reader));
        example.accept(HandInGui.DOCUMENT, (reader, drafted) -> HandInGui.example(drafted, reader));
    }

    /** Opens the objective dialog for {@code player}, or the dialog that says why there is nothing to hand in. */
    public void openObjectives(final Player player) {
        final Locale locale = identities.languageOf(player.getUniqueId());
        final SeasonState.Active active = season.active();
        final String activeKey = active.key();
        final MilestoneTrack currentTrack = track.get();
        final Milestone milestone =
                activeKey == null ? null : currentTrack.milestone(activeKey).orElse(null);
        final Optional<ObjectiveDialog.Empty> why =
                ObjectiveDialog.Empty.of(currentTrack, season.completedKeys(), milestone);
        if (why.isPresent() || milestone == null) {
            player.showDialog(
                    ObjectiveDialog.empty(renderer, documents, locale, why.orElse(ObjectiveDialog.Empty.NONE)));
            return;
        }
        show(
                player,
                locale,
                milestone,
                currentTrack.size(),
                currentTrack.positionOf(milestone.key()),
                active.objectives());
    }

    /** Reads the player's share from the database off the main thread, then opens the dialog on the main thread. */
    private void show(
            final Player player,
            final Locale locale,
            final Milestone milestone,
            final int totalMilestones,
            final int currentMilestone,
            final List<ObjectiveRow> rows) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        PaperScheduler.of(plugin).execute(() -> {
            final OwnShare.Summary share = discordId
                    .map(id -> OwnShare.of(contributions.ownContributions(milestone.key(), id)))
                    .orElseGet(OwnShare::unavailable);

            PaperScheduler.of(plugin).onMain(() -> {
                if (player.isOnline()) {
                    new ObjectiveDialog(
                                    renderer,
                                    documents,
                                    dialogs,
                                    locale,
                                    milestone,
                                    totalMilestones,
                                    currentMilestone,
                                    rows,
                                    share,
                                    this::confirm,
                                    work -> PaperScheduler.of(plugin).onMain(work))
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
        final List<ItemStack> taken = gui.apply(result);
        gui.crediting();
        final String objectiveKey = gui.objective().key();
        final long accepted = result.accepted();
        final DiscordId contributor = discordId.get();

        PaperScheduler.of(plugin).execute(() -> {
            // Negative when the credit failed, which tells the window nothing about the objective.
            long credited;
            try {
                credited = contributions.credit(contributor, objectiveKey, accepted, player.getUniqueId());
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .severe("hand-in not credited, items returned: player=" + player.getName() + " key="
                                + objectiveKey + " cause=" + failure.getMessage());
                credited = -1;
            }
            final long paid = credited;
            PaperScheduler.of(plugin)
                    .onMain(() -> applyCreditResult(player, contributor, gui, taken, objectiveKey, accepted, paid));
        });
    }

    /**
     * Shows the credit result in the window and returns any uncredited items. Main thread only.
     * Uncredited items for an offline player go to the pending-items store for their next join.
     */
    private void applyCreditResult(
            final Player player,
            final DiscordId discordId,
            final HandInGui gui,
            final List<ItemStack> taken,
            final String objectiveKey,
            final long accepted,
            final long paid) {
        if (!player.isOnline()) {
            final List<Integer> amounts =
                    taken.stream().map(ItemStack::getAmount).toList();
            final List<Integer> toReturn = offlineReturnAmounts(amounts, accepted, paid);
            for (int index = 0; index < taken.size(); index++) {
                final int back = toReturn.get(index);
                if (back > 0) {
                    final ItemStack copy = taken.get(index).clone();
                    copy.setAmount(back);
                    try {
                        storePending.accept(discordId, copy);
                    } catch (final RuntimeException error) {
                        plugin.getLogger()
                                .log(
                                        Level.SEVERE,
                                        "failed to store uncredited hand-in items for offline player: player="
                                                + player.getName()
                                                + " key="
                                                + objectiveKey
                                                + " item="
                                                + copy.getType().name()
                                                + "x"
                                                + copy.getAmount(),
                                        error);
                    }
                }
            }
            if (toReturn.stream().anyMatch(amount -> amount > 0)) {
                plugin.getLogger()
                        .info("hand-in player offline, uncredited items queued for next join: player="
                                + player.getName() + " key=" + objectiveKey + " credited=" + Math.max(0L, paid)
                                + " accepted=" + accepted);
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

    /**
     * Returns how many items from each taken stack must be stored for an offline player's next join.
     * A failed credit ({@code paid < 0}) returns every taken stack; a partial credit returns the last unpaid items.
     */
    static List<Integer> offlineReturnAmounts(final List<Integer> takenAmounts, final long accepted, final long paid) {
        if (paid < 0) {
            return List.copyOf(takenAmounts);
        }
        final long unpaid = Math.max(0L, accepted - paid);
        if (unpaid == 0L) {
            final List<Integer> zeros = new ArrayList<>(takenAmounts.size());
            for (int i = 0; i < takenAmounts.size(); i++) {
                zeros.add(0);
            }
            return List.copyOf(zeros);
        }
        return HandInGui.unpaid(takenAmounts, unpaid);
    }
}
