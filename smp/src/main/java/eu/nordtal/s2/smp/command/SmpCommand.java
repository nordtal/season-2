package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.state.SeasonState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import org.bukkit.plugin.Plugin;

/**
 * The SMP's Brigadier trees: its own commands, plus everything another process runs.
 *
 * {@code /phase} and {@code /network} are absent, since Velocity answers them before a backend sees them.
 */
public final class SmpCommand {

    private SmpCommand() {}

    /**
     * Builds the trees.
     *
     * @param effects the chat instance, on the plugin's async scheduler
     * @param track a supplier of the current track, since {@code /smp reload} replaces it after the tree is built
     */
    public static List<LiteralCommandNode<CommandSourceStack>> build(
            final Plugin plugin,
            final Messages messages,
            final PlayerLocales locales,
            final Identities identities,
            final SmpSounds sounds,
            final Outbox outbox,
            final BukkitSmpEffects effects,
            final java.util.function.Supplier<MilestoneTrack> track,
            final SeasonState season,
            final java.util.function.Supplier<ToneColours> colours) {

        final PaperCommands commands = new PaperCommands(
                plugin,
                messages,
                Target.SMP,
                outbox,
                mcUuid -> locales.of(mcUuid),
                mcUuid -> identities.of(mcUuid).admin(),
                identities::discordIdOf,
                sounds::play,
                colours);

        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, effects);
        }
        registerSuggestions(commands, track, season);
        commands.remoteAll(Catalogue.all());
        return commands.build();
    }

    /** Suggests milestone and objective keys from memory, since Brigadier asks once per keystroke per client. */
    private static void registerSuggestions(
            final PaperCommands commands,
            final java.util.function.Supplier<MilestoneTrack> track,
            final SeasonState season) {
        commands.suggest(SmpCommands.UNLOCK_MILESTONE, "key", () -> track.get().keys());
        commands.suggest(
                SmpCommands.COMPLETE_OBJECTIVE,
                "key",
                // The active milestone's objectives only; any other key is always refused.
                () -> season.active().objectives().stream()
                        .map(ObjectiveRow::key)
                        .toList());
    }
}
