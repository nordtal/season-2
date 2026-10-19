package eu.nordtal.season.smp.npc;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
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
 * Opens the objective menu when a player clicks the NPC and credits the items of a confirmed hand-in.
 *
 * The credit runs off the main thread. Its result is applied on the main thread.
 */
public final class NpcListener implements Listener {

    record Accepted(long amount) {}

    static final DocumentSpec<DocumentSpec.None> NO_OBJECTIVE =
            DocumentSpec.game("smp", "objectives-none", "Spawn NPC", DocumentSpec.Shape.CHAT);
    static final DocumentSpec<Accepted> ACCEPTED =
            DocumentSpec.game("smp", "hand-in-accepted", "Spawn NPC", DocumentSpec.Shape.CHAT, Accepted.class);
    /** Closing the window without confirming gives everything back, which is why nothing was taken here. */
    static final DocumentSpec<DocumentSpec.None> NOTHING_WANTED =
            DocumentSpec.game("smp", "hand-in-nothing-wanted", "Spawn NPC", DocumentSpec.Shape.CHAT);

    static final DocumentSpec<DocumentSpec.None> NOTHING_CREDITED =
            DocumentSpec.game("smp", "hand-in-nothing-credited", "Spawn NPC", DocumentSpec.Shape.CHAT);

    private final Plugin plugin;
    private final SpawnNpc npc;
    private final SeasonState season;
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final Contributions contributions;
    private final Identities identities;

    private final MessageRenderer renderer;
    private final AppliedDocuments documents;
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
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.npc = npc;
        this.season = season;
        this.track = track;
        this.contributions = contributions;
        this.identities = identities;
        this.renderer = renderer;
        this.documents = documents;
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
            player.sendMessage(renderer.format(locale, Sent.of(NO_OBJECTIVE)));
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        show(player, locale, milestone, active.objectives());
    }

    /** Reads the player's share from the database off the main thread, then opens the menu on the main thread. */
    private void show(
            final Player player, final Locale locale, final Milestone milestone, final List<ObjectiveRow> rows) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        PaperScheduler.of(plugin).execute(() -> {
            final OwnShare.Summary share = OwnShare.of(discordId
                    .map(id -> contributions.ownContributions(milestone.key(), id))
                    .orElse(List.of()));

            PaperScheduler.of(plugin).onMain(() -> {
                if (player.isOnline()) {
                    new ObjectiveGui(renderer, documents, locale, milestone, rows, share, this::confirm).open(player);
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
            player.sendMessage(renderer.format(locale, Sent.of(NOTHING_WANTED)));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        // The items are kept until the credit result is known, because the credit may be 0.
        final java.util.List<org.bukkit.inventory.ItemStack> taken = gui.apply(result);
        final String objectiveKey = gui.objective().key();
        final long accepted = result.accepted();

        PaperScheduler.of(plugin).execute(() -> {
            long credited;
            try {
                credited = contributions.credit(discordId.get(), objectiveKey, accepted, player.getUniqueId());
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .severe("the hand-in for " + player.getName() + " on "
                                + objectiveKey + " could not be credited, giving the items back: "
                                + failure.getMessage());
                credited = 0;
            }
            final long paid = credited;
            PaperScheduler.of(plugin).onMain(() -> applyCreditResult(player, locale, gui, taken, objectiveKey, paid));
        });
    }

    /** Sends the credit result to the player and returns the taken items if nothing was credited. Main thread only. */
    private void applyCreditResult(
            final Player player,
            final Locale locale,
            final HandInGui gui,
            final java.util.List<org.bukkit.inventory.ItemStack> taken,
            final String objectiveKey,
            final long paid) {
        if (paid <= 0) {
            // The objective finished while the screen was open.
            if (!player.isOnline()) {
                // An offline player cannot receive the items.
                plugin.getLogger()
                        .severe(player.getName() + " left while a hand-in on "
                                + objectiveKey + " was in flight, it credited nothing, and these"
                                + " items could not be returned: " + describe(taken));
                return;
            }
            gui.giveBack(player, taken);
            player.sendMessage(renderer.format(locale, Sent.of(NOTHING_CREDITED)));
            sounds.play(player, Feedback.REFUSED);
            player.closeInventory();
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        player.sendMessage(renderer.format(locale, ACCEPTED.with(new Accepted(paid))));
        sounds.play(player, Feedback.SMALL_SUCCESS);
        player.closeInventory();
    }

    private static String describe(final java.util.List<org.bukkit.inventory.ItemStack> stacks) {
        return stacks.stream()
                .map(stack -> stack.getAmount() + "x " + stack.getType().name())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
