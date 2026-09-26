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
 * <b>What is left of this class</b>
 *
 * Almost nothing, and that is the point. It used to be three hundred lines holding a tree, an admin gate, a
 * confirmation window, five handlers and the decisions inside them - all of which existed only here, on one surface,
 * and none of which could be asserted without a running server. The decisions are in {@code :commands} now and the
 * tree-building is in {@code :paper-common}; what is left is the wiring that says which commands this server owns
 * and where the rest live.
 *
 * <b>Why the other backends' commands are registered here at all</b>
 *
 * So an admin standing on the SMP can run {@code /hg start} or {@code /limbo reload} without switching servers -
 * and, more to the point, so that an admin can reach a backend that is the reason they cannot get to it. Those
 * become {@code command_request} rows.
 *
 * {@code /phase} and {@code /network} are deliberately absent: Velocity answers a command it knows before the packet
 * reaches a backend, so both are already available here from the proxy's single registration. Registering copies
 * would shadow nothing and be shadowed by everything.
 */
public final class SmpCommand {

    private SmpCommand() {}

    /**
     * @param effects the chat instance - built with the plugin's async scheduler. The inbox gets a
     *                second one built with {@code Runnable::run}; see {@link BukkitSmpEffects}
     * @param track   a <b>supplier</b> of the current track, not the track. The command tree is
     *                built once at enable and {@code /smp reload} replaces the plugin's track with
     *                a new instance, so a captured one would go on suggesting the milestone keys
     *                that were in the file at startup - offering keys that have been removed and
     *                omitting the ones that were added, until a restart
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

    /**
     * The two arguments a person cannot be expected to remember.
     *
     * Both sources are already in memory for the boards, so a keystroke costs a list walk rather than a query -
     * which is the rule a suggestion source has to meet, because Brigadier asks once per keystroke per client.
     */
    private static void registerSuggestions(
            final PaperCommands commands,
            final java.util.function.Supplier<MilestoneTrack> track,
            final SeasonState season) {
        commands.suggest(SmpCommands.UNLOCK_MILESTONE, "key", () -> track.get().keys());
        commands.suggest(
                SmpCommands.COMPLETE_OBJECTIVE,
                "key",
                // The ACTIVE milestone's objectives; the whole track would suggest keys that are always refused.
                () -> season.active().objectives().stream()
                        .map(ObjectiveRow::key)
                        .toList());
    }

    /**
     * /update, declared once and served like any other command.
     *
     * Target.LOCAL, since the effect is a row in a table this plugin already has a pool for, and an update is what
     * somebody asks for when the network is misbehaving.
     */
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

    /**
     * /aura and /smp status: what a player types here, native rather than declared - see {@link PlayerCommands}.
     * status hangs under the declared /smp root as an open subtree, which is also what keeps that root in a player's
     * tree now that everything else under it is the console's.
     */
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
