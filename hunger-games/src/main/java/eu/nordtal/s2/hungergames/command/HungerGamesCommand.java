package eu.nordtal.s2.hungergames.command;

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
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.hungergames.lobby.Lobby;
import eu.nordtal.s2.papercommon.command.PaperCommands;
import eu.nordtal.s2.papercommon.command.PaperUser;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The hunger games server's Brigadier trees: three admin commands, one player command, and
 * everything another process runs.
 *
 * <h2>What is left of this class</h2>
 * {@code /hg ready}, and the wiring. The three admin subcommands are declarations in
 * {@code :commands} now, so what they say and when they refuse is assertable without a server -
 * which for the command that starts the season's flagship event it previously was not.
 *
 * <h2>{@code /hg ready} stays here, and stays player-only</h2>
 * It marks the <b>sender</b> ready. The console is registered for no game and neither is a Discord
 * member, so there is nobody for it to mark - and it is the one {@code /hg} subcommand that is not
 * admin-only, which is why it cannot be in the catalogue. It is registered as an extra subtree under
 * the same root, which is also why {@link PaperCommands} puts its admin check on the nodes below a
 * root rather than on the root itself: gating {@code /hg} would have hidden this from every player.
 */
public final class HungerGamesCommand {

    private final Plugin plugin;
    private final HungerGamesDao dao;
    private final Messages messages;
    private final PlayerLocales locales;
    private final Lobby lobby;
    private final HungerGamesSounds sounds;
    private final Supplier<UUID> currentGameId;

    public HungerGamesCommand(final Plugin plugin, final HungerGamesDao dao,
                              final Messages messages, final PlayerLocales locales,
                              final Lobby lobby, final HungerGamesSounds sounds,
                              final Supplier<UUID> currentGameId) {
        this.plugin = plugin;
        this.dao = dao;
        this.messages = messages;
        this.locales = locales;
        this.lobby = lobby;
        this.sounds = sounds;
        this.currentGameId = currentGameId;
    }

    /** Every tree this server registers. */
    public List<LiteralCommandNode<CommandSourceStack>> build(final Outbox outbox,
                                                              final HungerGamesEffects effects,
                                                              final java.util.function.Predicate<UUID> isAdmin) {
        final PaperCommands commands = new PaperCommands(plugin, messages, Target.HUNGER_GAMES,
                outbox,
                mcUuid -> locales.of(mcUuid),
                isAdmin,
                dao::discordIdOf,
                sounds::play);

        for (final NordtalCommand<HungerGamesEffects> command : HungerGamesCommands.all()) {
            commands.local(command, effects);
        }
        // extraOpen, not extra: this is the one subtree any player may use.
        commands.extraOpen("hg", ready());
        commands.remoteAll(Catalogue.all());
        return commands.build();
    }

    /**
     * {@code /hg ready} - the player half.
     *
     * <p>The admin check is deliberately absent and the {@code requires} is a player check instead:
     * this is the command a participant runs, and the console genuinely cannot.</p>
     */
    private LiteralArgumentBuilder<CommandSourceStack> ready() {
        return Commands.literal("ready")
                .requires(source -> source.getSender() instanceof Player)
                .executes(this::handleReady);
    }

    private int handleReady(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        // Optional::empty rather than null: /hg ready needs no Discord id, it never travels, and a
        // bare null is ambiguous between PaperUser's two factories.
        final NordtalUser user = PaperUser.of(plugin, player, locales.of(player.getUniqueId()),
                false, java.util.Optional::<String>empty, messages, sounds::play);
        final UUID gameId = currentGameId.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (gameId == null) {
                user.reply("hg.lobby.not-registered", Map.of(), Feedback.REFUSED);
                return;
            }
            final var discordId = dao.discordIdOf(player.getUniqueId());
            final boolean marked = discordId.isPresent() && lobby.markReady(gameId, discordId.get());
            user.reply(marked ? "hg.lobby.ready-set" : "hg.lobby.not-registered", Map.of(),
                    marked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED);
        });
        return Command.SINGLE_SUCCESS;
    }
}
