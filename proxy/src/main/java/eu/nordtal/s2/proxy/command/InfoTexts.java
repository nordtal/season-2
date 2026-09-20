package eu.nordtal.s2.proxy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.proxy.gate.LoginRoster;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /discord} and {@code /rules}: two commands that print one line each.
 *
 * <h2>Native Brigadier, and why the framework left (season-2-ops/155)</h2>
 * A {@code Declaration} earns its cost when the same command appears on several surfaces, when its
 * arguments travel through a database row, and when a confirmation has to be honoured by every
 * adapter. These two have one surface, one target, no arguments and no confirmation - so what the
 * framework bought them was a declaration, an effects interface, an adapter translation and a
 * catalogue test for something that is a dozen lines of Brigadier here. The text they print was
 * already in this module's bundle; now the commands are too.
 *
 * <h2>Why the proxy owns them</h2>
 * Because they have to work in the waiting room. A player held there is precisely the player who
 * needs to be told where the Discord is, and a backend command cannot answer somebody who is not on
 * a backend. Being on the proxy also makes them one copy of one text rather than three.
 *
 * <h2>The invite is the same string the login screens use</h2>
 * {@code gate.yml#discord-invite-url}, substituted as {@code {invite}}. An invite that has been
 * re-issued is re-issued in exactly one place; a second copy of it in a message bundle is the copy
 * that stays pointing at a dead link, and the people reading it are by definition the people who
 * cannot get in.
 */
public final class InfoTexts {

    /** The key {@code /discord} prints. */
    public static final String DISCORD_TEXT = "info.discord";

    /** The key {@code /rules} prints. Ships as a marked placeholder until Till writes the rules. */
    public static final String RULES_TEXT = "info.rules";

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

    private BrigadierCommand command(final String literal, final String key) {
        return new BrigadierCommand(BrigadierCommand.literalArgumentBuilder(literal)
                .executes(context -> print(context, key)));
    }

    /**
     * The line itself.
     *
     * <p>Rendered against this module's bundle rather than through {@code NordtalUser#reply},
     * because the value is MiniMessage with a clickable link in it and the shared bundle carries no
     * markup at all - the same split these two always had, now without an interface in between.</p>
     */
    private int print(final CommandContext<CommandSource> context, final String key) {
        if (!(context.getSource() instanceof Player player)) {
            // Unchanged from the declaration these replaced: GAME and not CONSOLE. A line meant for
            // a chat window, with a link in it, is not what an operator wants back from a console -
            // and the console has `./nordtal.sh` and the file itself for everything real.
            new ConsoleUser(messages, context.getSource())
                    .reply("command.not-from-console", Map.of(), Feedback.REFUSED, Tone.BAD);
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(MessageRenderer.of(messages)
                .format(roster.localeOf(player.getUniqueId()), key, "invite", invite));
        return Command.SINGLE_SUCCESS;
    }
}
