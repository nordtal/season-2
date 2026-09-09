package eu.nordtal.s2.limbo.command;

import com.mojang.brigadier.tree.LiteralCommandNode;

import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.papercommon.command.PaperUser;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The waiting room's Brigadier trees: its one command, and everything another process runs.
 *
 * <h2>Two things this fold fixed rather than moved</h2>
 * <ul>
 *   <li>The reply was rendered against <b>the Minecraft client's own language</b>
 *       ({@code player.locale()}), which docs/i18n.md forbids in as many words: a player's language
 *       is {@code discord_user.locale}, mirrored from their Discord onboarding role, and the
 *       client's setting is consulted nowhere in this repository. It now goes through
 *       {@link PlayerLocales} like every other reply.</li>
 *   <li>The gate was the {@code limbo.admin} permission node - the only one this repository owned.
 *       It is now the same admin flag as everywhere else; see
 *       {@link LimboCommands} for why that loses nothing.</li>
 * </ul>
 *
 * <h2>Why this server registers other processes' commands too</h2>
 * It is the one place an admin can be while a backend is unreachable: every login on the network
 * crosses limbo. So {@code /smp reload} and {@code /hg start} are reachable from here, as rows.
 */
public final class LimboCommand {

    private LimboCommand() {
    }

    public static List<LiteralCommandNode<CommandSourceStack>> build(
            final Plugin plugin, final Messages messages, final PlayerLocales locales,
            final Predicate<UUID> isAdmin,
            final java.util.function.Function<UUID, Optional<String>> discordIdOf,
            final Outbox outbox, final LimboEffects effects,
            final javax.sql.DataSource pool) {

        final PaperCommands commands = new PaperCommands(plugin, messages, Target.LIMBO, outbox,
                locales::of, isAdmin,
                // A real source, and it has to be one: /access <sub> <member> is registered here
                // (it targets the bot) and its argument is resolved through account_link, so an
                // always-empty answer refused all four of them permanently. PaperUser reads this
                // lazily and Outbox#send is the only caller, so the query never lands on the login
                // path's own main thread - which is what the empty version was protecting.
                discordIdOf,
                PaperUser.Chime.silent());

        for (final NordtalCommand<LimboEffects> command : LimboCommands.all()) {
            commands.local(command, effects);
        }
        // /update, on the waiting room too. Almost nothing lives here by design - but Surface.GAME
        // is four processes, and an admin held in limbo during MAINTENANCE is exactly somebody who
        // may want to update the network they cannot get onto - which is also why the watcher is
        // wired here rather than left as a no-op: the acknowledgement without the answer is worse
        // than no command.
        final eu.nordtal.s2.papercommon.command.UpdateWatcher updates =
                new eu.nordtal.s2.papercommon.command.UpdateWatcher(plugin,
                        eu.nordtal.s2.common.update.UpdateDirectory.using(pool));
        eu.nordtal.s2.commands.update.UpdateCommands.all().forEach(command -> commands.local(command,
                new eu.nordtal.s2.commands.update.DirectoryUpdateEffects(
                        updates.directory(),
                        work -> org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, work),
                        (what, failure) -> plugin.getLogger()
                                .warning("An update command failed while " + what + ": " + failure),
                        updates::watch)));

        commands.remoteAll(Catalogue.all());
        return commands.build();
    }
}
