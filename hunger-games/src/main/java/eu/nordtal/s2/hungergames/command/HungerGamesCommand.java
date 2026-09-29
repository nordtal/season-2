package eu.nordtal.s2.hungergames.command;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.hungergames.lobby.Lobby;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.papercommon.command.PaperUser;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The hunger games server's Brigadier trees: the player's {@code /hg ready} and the wiring.
 *
 * {@code /hg ready} is an extra subtree under the admin root, so {@link PaperCommands} gates below the root.
 */
public final class HungerGamesCommand {

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final Messages messages;
    private final PlayerLocales locales;
    private final Lobby lobby;
    private final HungerGamesSounds sounds;
    private final Supplier<UUID> currentGameId;
    private final Supplier<ToneColours> colours;

    public HungerGamesCommand(
            final Plugin plugin,
            final HungerGamesDao dao,
            final Messages messages,
            final PlayerLocales locales,
            final Lobby lobby,
            final HungerGamesSounds sounds,
            final Supplier<UUID> currentGameId,
            final Supplier<ToneColours> colours) {
        this.plugin = plugin;
        this.dao = dao;
        this.messages = messages;
        this.locales = locales;
        this.lobby = lobby;
        this.sounds = sounds;
        this.currentGameId = currentGameId;
        this.colours = colours;
    }

    /** Every tree this server registers. */
    public List<LiteralCommandNode<CommandSourceStack>> build(
            final Outbox outbox, final HungerGamesEffects effects, final java.util.function.Predicate<UUID> isAdmin) {
        final PaperCommands commands = new PaperCommands(
                plugin,
                messages,
                Target.HUNGER_GAMES,
                outbox,
                mcUuid -> locales.of(mcUuid),
                isAdmin,
                dao::discordIdOf,
                sounds::play,
                colours);

        for (final NordtalCommand<HungerGamesEffects> command : HungerGamesCommands.all()) {
            commands.local(command, effects);
        }

        commands.extraOpen("hg", ready());
        commands.remoteAll(Catalogue.all());
        return commands.build();
    }

    /** {@code /hg ready}, which checks for a player instead of the admin flag. */
    private LiteralArgumentBuilder<CommandSourceStack> ready() {
        return Commands.literal("ready")
                .requires(source -> source.getSender() instanceof Player)
                .executes(this::handleReady);
    }

    private int handleReady(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        // Optional::empty, not null: null is ambiguous between PaperUser's factories.
        final NordtalUser user = PaperUser.of(
                plugin,
                player,
                locales.of(player.getUniqueId()),
                false,
                java.util.Optional::<String>empty,
                messages,
                sounds::play,
                colours);
        final UUID gameId = currentGameId.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (gameId == null) {
                user.reply(MESSAGES.hg().lobby().notRegistered(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            final var discordId = dao.discordIdOf(player.getUniqueId());
            final boolean marked = discordId.isPresent() && lobby.markReady(gameId, discordId.get());
            user.reply(
                    marked
                            ? MESSAGES.hg().lobby().readySet()
                            : MESSAGES.hg().lobby().notRegistered(),
                    marked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED,
                    marked ? Tone.GOOD : Tone.BAD);
        });
        return Command.SINGLE_SUCCESS;
    }
}
