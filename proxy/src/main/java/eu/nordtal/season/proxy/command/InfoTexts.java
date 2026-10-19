package eu.nordtal.season.proxy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The commands {@code /discord} and {@code /rules}.
 * Each prints one line. {@code /discord} prints {@code gate#discord-invite-url}.
 */
public final class InfoTexts {

    record Invite(@Example("https://discord.gg/nordtal") String invite) {}

    /** The text of {@code /discord}. */
    static final DocumentSpec<Invite> DISCORD = text("info-discord");

    /** The text of {@code /rules}. */
    static final DocumentSpec<Invite> RULES = text("info-rules");

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
        return List.of(command("discord", DISCORD), command("rules", RULES));
    }

    private BrigadierCommand command(final String literal, final DocumentSpec<Invite> text) {
        return new BrigadierCommand(
                BrigadierCommand.literalArgumentBuilder(literal).executes(context -> print(context, text)));
    }

    /** Prints the text to the player. */
    private int print(final CommandContext<CommandSource> context, final DocumentSpec<Invite> text) {
        if (!(context.getSource() instanceof Player player)) {
            context.getSource().sendMessage(renderer.format(Locale.ENGLISH, Sent.of(Replies.NOT_FROM_CONSOLE)));
            return Command.SINGLE_SUCCESS;
        }
        player.sendMessage(renderer.format(roster.localeOf(player.getUniqueId()), text.with(new Invite(invite))));
        return Command.SINGLE_SUCCESS;
    }

    private static DocumentSpec<Invite> text(final String name) {
        return DocumentSpec.game("proxy", name, "Commands", DocumentSpec.Shape.CHAT, Invite.class);
    }
}
