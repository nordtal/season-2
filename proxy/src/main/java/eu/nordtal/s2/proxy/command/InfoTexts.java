package eu.nordtal.s2.proxy.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.proxy.ProxyMessages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code /discord} and {@code /rules}: two commands that print one line each.
 *
 * The proxy owns them so they work in the waiting room; the invite is {@code gate.yml#discord-invite-url}.
 */
public final class InfoTexts {

    /** What {@code /discord} prints. */
    public static final Function<Object, MessageRef> DISCORD_TEXT = ProxyMessages.MESSAGES.info()::discord;

    /** What {@code /rules} prints. */
    public static final Function<Object, MessageRef> RULES_TEXT = ProxyMessages.MESSAGES.info()::rules;

    private final Messages messages;
    private final String invite;
    private final LoginRoster roster;

    public InfoTexts(final Messages messages, final String invite, final LoginRoster roster) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.invite = Objects.requireNonNull(invite, "invite");
        this.roster = Objects.requireNonNull(roster, "roster");
    }

    /** Both commands, ready for {@code CommandManager#register}. */
    public List<BrigadierCommand> commands() {
        return List.of(command("discord", DISCORD_TEXT), command("rules", RULES_TEXT));
    }

    private BrigadierCommand command(final String literal, final Function<Object, MessageRef> text) {
        return new BrigadierCommand(
                BrigadierCommand.literalArgumentBuilder(literal).executes(context -> print(context, text)));
    }

    /** Prints the line from this module's bundle, whose value carries a clickable link. */
    private int print(final CommandContext<CommandSource> context, final Function<Object, MessageRef> text) {
        if (!(context.getSource() instanceof Player player)) {
            // GAME and not CONSOLE: a clickable link is not what an operator wants from a console.
            new ConsoleUser(messages, context.getSource())
                    .reply(MESSAGES.command().notFromConsole(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(
                MessageRenderer.of(messages).format(roster.localeOf(player.getUniqueId()), text.apply(invite)));
        return Command.SINGLE_SUCCESS;
    }
}
