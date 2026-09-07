package eu.nordtal.s2.smp.command;

import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.commands.update.UpdateCommands;
import eu.nordtal.s2.commands.update.UpdateEffects;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.state.SeasonState;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * The SMP's Brigadier trees: its own six commands, plus everything another process runs.
 *
 * <h2>What is left of this class</h2>
 * Almost nothing, and that is the point. It used to be three hundred lines holding a tree, an admin
 * gate, a confirmation window, five handlers and the decisions inside them - all of which existed
 * only here, on one surface, and none of which could be asserted without a running server. The
 * decisions are in {@code :commands} now and the tree-building is in {@code :paper-common}; what is
 * left is the wiring that says which commands this server owns and where the rest live.
 *
 * <h2>Why the other backends' commands are registered here at all</h2>
 * So an admin standing on the SMP can run {@code /hg start} or {@code /limbo reload} without
 * switching servers - and, more to the point, so that an admin can reach a backend that is the
 * reason they cannot get to it. Those become {@code command_request} rows.
 *
 * <p>{@code /phase} and {@code /network} are deliberately absent: Velocity answers a command it
 * knows before the packet reaches a backend, so both are already available here from the proxy's
 * single registration. Registering copies would shadow nothing and be shadowed by everything.</p>
 */
public final class SmpCommand {

    private SmpCommand() {
    }

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
            final Plugin plugin, final Messages messages, final PlayerLocales locales,
            final Identities identities, final SmpSounds sounds, final Outbox outbox,
            final SmpEffects effects, final UpdateWatcher updates,
            final java.util.function.Supplier<MilestoneTrack> track,
            final SeasonState season) {

        final PaperCommands commands = new PaperCommands(plugin, messages, Target.SMP, outbox,
                mcUuid -> locales.of(mcUuid),
                mcUuid -> identities.of(mcUuid).admin(),
                identities::discordIdOf,
                sounds::play);

        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, effects);
        }

        // One effects object for /update, built here because only this class knows both halves:
        // the pool (through the watcher) and where a Paper plugin is allowed to wait.
        final UpdateEffects updateEffects = new eu.nordtal.s2.commands.update.DirectoryUpdateEffects(
                updates.directory(), eu.nordtal.s2.common.update.UpdateSource.GAME,
                work -> org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, work),
                (what, failure) -> plugin.getLogger()
                        .warning("An update command failed while " + what + ": " + failure),
                updates::watch);

        // The two arguments a person cannot be expected to remember. Both sources are already in
        // memory for the boards, so a keystroke costs a list walk rather than a query - which is the
        // rule a suggestion source has to meet, because Brigadier asks once per keystroke per
        // client.
        commands.suggest(SmpCommands.UNLOCK_MILESTONE, "key", () -> track.get().keys());
        commands.suggest(SmpCommands.COMPLETE_OBJECTIVE, "key",
                // The ACTIVE milestone's objectives, because that is the only milestone this
                // command can close one of - offering the whole track would suggest keys that are
                // always refused.
                () -> season.active().objectives().stream().map(ObjectiveRow::key).toList());

        // /update, folded into :commands on 2026-09-08. It used to hang under /smp as a subtree
        // this adapter knew nothing about, with a comment saying it should never become a
        // NordtalCommand - because "the updater's report must not be rendered twice". That rule was
        // deliberately rewritten the day before: what must not happen twice is the DECIDING, and
        // the report is now data that every surface draws. So the command is declared once and
        // this server serves it like any other.
        //
        // Target.LOCAL, so it never travels: the effect is a row in a table this plugin already has
        // a pool for, and an update is what somebody asks for when the network is misbehaving.
        for (final NordtalCommand<UpdateEffects> command : UpdateCommands.all()) {
            commands.local(command, updateEffects);
        }

        commands.remoteAll(Catalogue.all());
        return commands.build();
    }
}
