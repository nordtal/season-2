package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.commands.update.UpdateCommands;
import eu.nordtal.s2.commands.update.UpdateEffects;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.papercommon.command.UpdateWatcher;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.state.SeasonState;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
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
            final UpdateWatcher updates,
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
        registerUpdateCommands(commands, plugin, updates);

        final PlayerCommands own =
                registerPlayerCommands(commands, effects, plugin, messages, locales, identities, sounds, colours);
        commands.remoteAll(Catalogue.all());
        final List<LiteralCommandNode<CommandSourceStack>> roots = new ArrayList<>(commands.build());
        roots.add(own.aura());
        return List.copyOf(roots);
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

    /** Declares {@code /update}, locally, since its effect is a row this plugin already has a pool for. */
    private static void registerUpdateCommands(
            final PaperCommands commands, final Plugin plugin, final UpdateWatcher updates) {
        final UpdateEffects updateEffects = new eu.nordtal.s2.commands.update.DirectoryUpdateEffects(
                updates.directory(),
                work -> org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, work),
                (what, failure) ->
                        plugin.getLogger().warning("An update command failed while " + what + ": " + failure),
                updates::watch);
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, updateEffects);
        }
    }

    /** Registers {@code /aura} and {@code /smp status}, whose node keeps the {@code /smp} root in a player's tree. */
    private static PlayerCommands registerPlayerCommands(
            final PaperCommands commands,
            final BukkitSmpEffects effects,
            final Plugin plugin,
            final Messages messages,
            final PlayerLocales locales,
            final Identities identities,
            final SmpSounds sounds,
            final java.util.function.Supplier<ToneColours> colours) {
        final PlayerCommands own = new PlayerCommands(
                effects,
                effects,
                sender -> sender instanceof Player player
                        ? PaperUser.of(
                                plugin,
                                player,
                                locales.of(player.getUniqueId()),
                                identities.of(player.getUniqueId()).admin(),
                                () -> identities.discordIdOf(player.getUniqueId()),
                                messages,
                                sounds::play,
                                colours)
                        : PaperUser.console(plugin, sender, messages, colours));
        commands.extraOpen("smp", own.status());
        return own;
    }
}
