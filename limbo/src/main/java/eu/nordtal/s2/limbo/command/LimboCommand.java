package eu.nordtal.s2.limbo.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.papercommon.command.PaperUser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.plugin.Plugin;

/**
 * The waiting room's Brigadier trees: its one command, and everything another process runs.
 *
 * Every login crosses limbo, so it is where an admin can reach {@code /smp reload} while a backend is down.
 */
public final class LimboCommand {

    private LimboCommand() {}

    public static List<LiteralCommandNode<CommandSourceStack>> build(
            final Plugin plugin,
            final Messages messages,
            final PlayerLocales locales,
            final Predicate<UUID> isAdmin,
            final java.util.function.Function<UUID, Optional<String>> discordIdOf,
            final Outbox outbox,
            final LimboEffects effects,
            final java.util.function.Supplier<ToneColours> colours) {

        final PaperCommands commands = new PaperCommands(
                plugin,
                messages,
                Target.LIMBO,
                outbox,
                locales::of,
                isAdmin,
                // Real, not empty: PaperUser reads it lazily, so the query never lands on the login path's main thread.
                discordIdOf,
                PaperUser.Chime.silent(),
                colours);

        for (final NordtalCommand<LimboEffects> command : LimboCommands.all()) {
            commands.local(command, effects);
        }
        commands.remoteAll(Catalogue.all());
        return commands.build();
    }
}
