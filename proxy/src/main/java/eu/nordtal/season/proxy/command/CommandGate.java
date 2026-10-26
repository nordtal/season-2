package eu.nordtal.season.proxy.command;

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.proxy.gate.LoginRoster;
import eu.nordtal.season.settings.network.CommandAllowlist;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Allows players who are not admins only the commands of the allowlist.
 * A refused command gets the answer of an unknown command.
 */
public final class CommandGate {

    static final DocumentSpec<DocumentSpec.None> UNKNOWN = DocumentSpec.game(
                    "proxy", "command-unknown", "Commands", DocumentSpec.Shape.CHAT)
            .titled("Unknown command on the network");

    /** Plays the sound of a refusal. */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

        /** Plays nothing. */
        static Chime silent() {
            return (player, feedback) -> {};
        }
    }

    private final LoginRoster roster;
    private final MessageRenderer renderer;
    private final Logger logger;
    private final Chime chime;

    /** The allowlist of the network. */
    private final Supplier<CommandAllowlist> allowlist;

    /** Creates the gate without sounds. */
    public CommandGate(
            final LoginRoster roster,
            final CommandAllowlist allowlist,
            final MessageRenderer renderer,
            final Logger logger) {
        this(roster, allowlist, renderer, logger, Chime.silent());
    }

    /** Creates the gate with a fixed allowlist. */
    public CommandGate(
            final LoginRoster roster,
            final CommandAllowlist allowlist,
            final MessageRenderer renderer,
            final Logger logger,
            final Chime chime) {
        this(roster, () -> allowlist, renderer, logger, chime);
    }

    public CommandGate(
            final LoginRoster roster,
            final Supplier<CommandAllowlist> allowlist,
            final MessageRenderer renderer,
            final Logger logger,
            final Chime chime) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.chime = Objects.requireNonNull(chime, "chime");
    }

    /** Sets every permission of a player who is not an admin to false. */
    @Subscribe
    public void onPermissionsSetup(final PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player player)) {
            return;
        }
        event.setProvider(
                subject -> permission -> roster.isAdmin(player.getUniqueId()) ? Tristate.TRUE : Tristate.FALSE);
    }

    /** Denies every command that is not on the allowlist. */
    @Subscribe
    public void onCommandExecute(final CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (roster.isAdmin(player.getUniqueId()) || allowlist.get().allows(event.getCommand())) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(renderer.format(locale(player), Sent.of(UNKNOWN)));
        chime.play(player, Feedback.REFUSED);
    }

    /** Removes the commands that are not on the allowlist from the command tree of the client. */
    @Subscribe
    public void onAvailableCommands(final PlayerAvailableCommandsEvent event) {
        if (roster.isAdmin(event.getPlayer().getUniqueId())) {
            return;
        }
        final CommandAllowlist current = allowlist.get();
        final List<String> remove = new ArrayList<>();
        for (final CommandNode<?> child : event.getRootNode().getChildren()) {
            if (!current.allowsRoot(child.getName())) {
                remove.add(child.getName());
            }
        }
        remove.forEach(event.getRootNode()::removeChildByName);
        if (!remove.isEmpty()) {
            logger.debug(
                    "Hid {} command(s) from {}: {}",
                    remove.size(),
                    event.getPlayer().getUsername(),
                    remove);
        }
    }

    /** Returns the language of the player from {@link LoginRoster}. */
    private Locale locale(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }
}
