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
            final Predicate<UUID> isAdmin, final Outbox outbox, final LimboEffects effects) {

        final PaperCommands commands = new PaperCommands(plugin, messages, Target.LIMBO, outbox,
                locales::of, isAdmin,
                // No account link is looked up here. Nothing limbo runs needs one, and the lookup
                // would be a query on the login path's own server - the one place this repository
                // has spent the most effort keeping queries out of.
                mcUuid -> Optional.empty(),
                PaperUser.Chime.silent());

        for (final NordtalCommand<LimboEffects> command : LimboCommands.all()) {
            commands.local(command, effects);
        }
        commands.remoteAll(Catalogue.all());
        return commands.build();
    }
}
