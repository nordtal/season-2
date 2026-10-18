package eu.nordtal.season.proxy.command;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.proxy.ProxyMessages;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * The commands {@code /discord} and {@code /rules}. Each prints one line. {@code /discord} prints {@code
 * gate#discord-invite-url}.
 */
public final class InfoTexts {

    /** The text of {@code /discord}. */
    public static final Function<String, MessageRef> DISCORD_TEXT = ProxyMessages.MESSAGES.info()::discord;

    /** The text of {@code /rules}. */
    public static final Function<String, MessageRef> RULES_TEXT = ProxyMessages.MESSAGES.info()::rules;

    private final MessageRenderer renderer;
    private final String invite;
    private final LoginRoster roster;

    public InfoTexts(final MessageRenderer renderer, final String invite, final LoginRoster roster) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.invite = Objects.requireNonNull(invite, "invite");
        this.roster = Objects.requireNonNull(roster, "roster");
    }

    /** Returns both commands. */
    public List<BrigadierCommand> commands() {
        return List.of(command("discord", DISCORD_TEXT), command("rules", RULES_TEXT));
    }

    private BrigadierCommand command(final String literal, final Function<String, MessageRef> text) {
        return new BrigadierCommand(
                BrigadierCommand.literalArgumentBuilder(literal).executes(context -> print(context, text)));
    }

    /** Prints the text to the player. */
    private int print(final CommandContext<CommandSource> context, final Function<String, MessageRef> text) {
        if (!(context.getSource() instanceof Player player)) {
            context.getSource()
                    .sendMessage(
                            renderer.format(Locale.ENGLISH, MESSAGES.command().notFromConsole()));
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(renderer.format(roster.localeOf(player.getUniqueId()), text.apply(invite)));
        return Command.SINGLE_SUCCESS;
    }
}
