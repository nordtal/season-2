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
     */
    public static List<LiteralCommandNode<CommandSourceStack>> build(
            final Plugin plugin, final Messages messages, final PlayerLocales locales,
            final Identities identities, final SmpSounds sounds, final Outbox outbox,
            final SmpEffects effects, final UpdateCommands updates, final MilestoneTrack track,
            final SeasonState season) {

        final PaperCommands commands = new PaperCommands(plugin, messages, Target.SMP, outbox,
                mcUuid -> locales.of(mcUuid),
                mcUuid -> identities.of(mcUuid).admin(),
                identities::discordIdOf,
                sounds::play);

        for (final NordtalCommand<SmpEffects> command : SmpCommands.all()) {
            commands.local(command, effects);
        }

        // The two arguments a person cannot be expected to remember. Both sources are already in
        // memory for the boards, so a keystroke costs a list walk rather than a query - which is the
        // rule a suggestion source has to meet, because Brigadier asks once per keystroke per
        // client.
        commands.suggest(SmpCommands.UNLOCK_MILESTONE, "key", track::keys);
        commands.suggest(SmpCommands.COMPLETE_OBJECTIVE, "key",
                // The ACTIVE milestone's objectives, because that is the only milestone this
                // command can close one of - offering the whole track would suggest keys that are
                // always refused.
                () -> season.active().objectives().stream().map(ObjectiveRow::key).toList());

        // /smp update is not a NordtalCommand and should not become one: it already travels,
        // through update_request to a container that is not a command target, and its answer is the
        // updater's own report - which docs/updater.md forbids rendering a second time.
        commands.extra("smp", updates.build());

        commands.remoteAll(Catalogue.all());
        return commands.build();
    }
}
