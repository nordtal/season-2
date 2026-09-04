package eu.nordtal.s2.limbo.command;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * {@code /limbo reload} - the one command this server has.
 *
 * <h2>Why a waiting room has a command at all</h2>
 * Every line a player sees here is a title on a black screen, and the whole user interface of this
 * server is eight of them. A wording change that needs a restart is a wording change that takes the
 * waiting room down while somebody is waiting in it, which is the one moment it must not go away.
 *
 * <h2>Console, not chat</h2>
 * Nobody is standing on this server to type this: a player here is mid-login and has no chat. The
 * permission is {@code limbo.admin}, which the console always holds and an operator holds by
 * default - and the reply goes back through {@link Messages} like everything else rather than being
 * a hardcoded English string, because that is the rule this command exists to serve.
 *
 * <h2>Only the wording</h2>
 * {@code config.yml} names the waiting world and the title refresh interval, and re-reading those
 * while the room is running would mean rebuilding it under the players in it. A message is safe to
 * swap mid-flight; a world is not.
 */
public final class LimboCommand {

    private final Plugin plugin;
    private final Messages messages;
    private final MessageRenderer renderer;

    public LimboCommand(final Plugin plugin, final Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.renderer = new MessageRenderer(messages);
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("limbo")
                .requires(source -> source.getSender().hasPermission("limbo.admin"))
                .then(Commands.literal("reload").executes(this::handleReload))
                .build();
    }

    private int handleReload(final CommandContext<CommandSourceStack> context) {
        final var sender = context.getSource().getSender();
        final Locale locale = sender instanceof Player player
                ? Locale.forLanguageTag(player.locale().toLanguageTag())
                : Locale.ENGLISH;

        // Off the main thread: this reads files, and this server's whole job is to be responsive
        // to players who are already staring at a black screen.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String key;
            try {
                messages.reload();
                messages.unknownOverrideKeys().forEach(unknown -> plugin.getLogger().warning(
                        "the message override names " + unknown + ", which no bundle declares - it"
                                + " is stored and never used; check the spelling"));
                key = "limbo.admin.reloaded";
            } catch (final RuntimeException exception) {
                plugin.getLogger().severe("the messages could not be reloaded, the running ones are "
                        + "unchanged: " + exception.getMessage());
                key = "limbo.admin.reload-failed";
            }
            final String reply = key;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(renderer.get(locale, reply)));
        });
        return Command.SINGLE_SUCCESS;
    }
}
