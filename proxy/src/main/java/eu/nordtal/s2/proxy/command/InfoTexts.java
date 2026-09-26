package eu.nordtal.s2.proxy.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.proxy.ProxyMessages;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code /discord} and {@code /rules}: two commands that print one line each.
 *
 * Plain Brigadier rather than a {@code Declaration}: these two have one surface, one target, no
 * arguments and no confirmation, so a declaration, an effects interface, an adapter translation and
 * a catalogue test would buy nothing that a dozen lines of Brigadier does not already do here.
 *
 * The proxy owns them because they have to work in the waiting room: a player held there is
 * precisely the player who needs to be told where the Discord is, and a backend command cannot
 * answer somebody who is not on a backend. Being on the proxy also makes them one copy of one text
 * rather than three.
 *
 * The invite is the same string the login screens use, {@code gate.yml#discord-invite-url},
 * substituted as {@code {invite}}: an invite that has been re-issued is re-issued in exactly one
 * place, rather than leaving a second copy pointing at a dead link.
 */
public final class InfoTexts {

    /** What {@code /discord} prints. */
    public static final Function<Object, MessageRef> DISCORD_TEXT = ProxyMessages.MESSAGES.info()::discord;

    /** What {@code /rules} prints. Ships as a marked placeholder until the rules are written. */
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

    /**
     * The line itself.
     *
     * Rendered against this module's bundle rather than through {@code NordtalUser#reply}, because
     * the value is MiniMessage with a clickable link in it and the shared bundle carries no markup
     * at all.
     */
    private int print(final CommandContext<CommandSource> context, final Function<Object, MessageRef> text) {
        if (!(context.getSource() instanceof Player player)) {
            // GAME and not CONSOLE: a line with a clickable link is not what an operator wants from a console.
            new ConsoleUser(messages, context.getSource())
                    .reply(MESSAGES.command().notFromConsole(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(
                MessageRenderer.of(messages).format(roster.localeOf(player.getUniqueId()), text.apply(invite)));
        return Command.SINGLE_SUCCESS;
    }
}
