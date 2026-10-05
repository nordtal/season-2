package eu.nordtal.season.smp.npc;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.port.Contributions;
import eu.nordtal.season.smp.port.PrizeSource;
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
 * Clicking the NPC opens the objective list; a deposit screen's confirm comes back here, where items change hands.
 *
 * The menu draws the milestone {@link SeasonState} holds; the credit runs off the main thread, its answer on it.
 */
public final class NpcListener implements Listener {

    private final Plugin plugin;
    private final SpawnNpc npc;
    private final SeasonState season;
    /** The milestone track, as a supplier, because a settings change replaces it mid-season. */
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final Contributions contributions;
    private final PrizeSource prizes;
    private final Identities identities;

    private final MessageRenderer renderer;
    private final SmpSounds sounds;

    public NpcListener(
            final Plugin plugin,
            final SpawnNpc npc,
            final SeasonState season,
            final java.util.function.Supplier<MilestoneTrack> track,
            final Contributions contributions,
            final PrizeSource prizes,
            final Identities identities,
            final MessageRenderer renderer,
            final SmpSounds sounds) {
        this.plugin = plugin;
        this.npc = npc;
        this.season = season;
        this.track = track;
        this.contributions = contributions;
        this.prizes = prizes;
        this.identities = identities;
        this.renderer = renderer;
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
            player.sendMessage(
                    renderer.format(locale, MESSAGES.smp().objectives().none()));
            sounds.play(player, Feedback.REFUSED);
            return;
        }
        show(player, locale, milestone, active.objectives());
    }

    /** Reads the player's own share, the one line only the database can answer, and opens the menu with it. */
    private void show(
            final Player player, final Locale locale, final Milestone milestone, final List<ObjectiveRow> rows) {
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        PaperScheduler.of(plugin).execute(() -> {
            final OwnShare.Summary share = OwnShare.of(
                    discordId
                            .map(id -> contributions.ownContributions(milestone.key(), id))
                            .orElse(List.of()),
                    prizes::extraSpinsFor);

            PaperScheduler.of(plugin).onMain(() -> {
                if (player.isOnline()) {
                    new ObjectiveGui(renderer, locale, milestone, rows, share, this::confirm).open(player);
                }
            });
        });
    }

    /** The one moment items change hands, applying what {@link HandIn} decided and crediting it. */
    private void confirm(final Player player, final HandInGui gui) {
        final Locale locale = identities.languageOf(player.getUniqueId());
        final Optional<DiscordId> discordId = identities.discordIdOf(player.getUniqueId());
        if (discordId.isEmpty()) {
            player.sendMessage(renderer.format(locale, MESSAGES.smp().error().noAccountLink()));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        final HandIn.Result result = HandIn.sort(gui.offered(), gui.wanted(), gui.stillNeeded());
        if (result.accepted() <= 0) {
            player.sendMessage(renderer.format(locale, MESSAGES.smp().handin().nothingWanted()));
            sounds.play(player, Feedback.REFUSED);
            return;
        }

        // Taken now, on the click, and held back since the credit may pay for none of them.
        final java.util.List<org.bukkit.inventory.ItemStack> taken = gui.apply(result);
        final String objectiveKey = gui.objective().key();
        final long accepted = result.accepted();

        PaperScheduler.of(plugin).execute(() -> {
            long credited;
            try {
                credited = contributions.credit(discordId.get(), objectiveKey, accepted, player.getUniqueId());
            } catch (final RuntimeException failure) {
                // The database said no; without this the items vanish and the player is told nothing.
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

    /** Main thread: tells the player what the credit above decided, and returns items it could not pay for. */
    private void applyCreditResult(
            final Player player,
            final Locale locale,
            final HandInGui gui,
            final java.util.List<org.bukkit.inventory.ItemStack> taken,
            final String objectiveKey,
            final long paid) {
        if (paid <= 0) {
            // Nothing was credited: the objective finished while open.
            if (!player.isOnline()) {
                // The one case nothing here can fix: the player is offline and this plugin has no mailbox.
                plugin.getLogger()
                        .severe(player.getName() + " left while a hand-in on "
                                + objectiveKey + " was in flight, it credited nothing, and these"
                                + " items could not be returned: " + describe(taken));
                return;
            }
            gui.giveBack(player, taken);
            player.sendMessage(renderer.format(locale, MESSAGES.smp().handin().nothingCredited()));
            sounds.play(player, Feedback.REFUSED);
            player.closeInventory();
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        player.sendMessage(renderer.format(locale, MESSAGES.smp().handin().accepted(paid)));
        sounds.play(player, Feedback.SMALL_SUCCESS);
        player.closeInventory();
    }

    /** {@code 12x DIAMOND, 3x EMERALD}, for a log line an admin has to act on. */
    private static String describe(final java.util.List<org.bukkit.inventory.ItemStack> stacks) {
        return stacks.stream()
                .map(stack -> stack.getAmount() + "x " + stack.getType().name())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
