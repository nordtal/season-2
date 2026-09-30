package eu.nordtal.s2.proxy.command;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.database.command.CommandAllowlist;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messagerendering.Tones;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * What a player who is not an admin may type, and what they are told exists.
 *
 * A refused command answers like a mistyped one. The admin check reads {@link LoginRoster} and never the database.
 */
public final class CommandGate {

    /** How a refusal sounds on the proxy; the Velocity twin of {@code PaperUser.Chime}. */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

        /** Plays nothing. */
        static Chime silent() {
            return (player, feedback) -> {};
        }
    }

    private final LoginRoster roster;
    private final Messages messages;
    private final Logger logger;
    private final Supplier<ToneColours> colours;
    private final Chime chime;

    /** Read from {@code network.yml}, which this proxy owns, and fixed until restart. */
    private final CommandAllowlist allowlist;

    /** Without a {@link Chime}: silent. */
    public CommandGate(
            final LoginRoster roster, final CommandAllowlist allowlist, final Messages messages, final Logger logger) {
        this(roster, allowlist, messages, logger, Chime.silent());
    }

    /** Without a colour supplier: {@link ToneColours#DEFAULTS}. */
    public CommandGate(
            final LoginRoster roster,
            final CommandAllowlist allowlist,
            final Messages messages,
            final Logger logger,
            final Chime chime) {
        this(roster, allowlist, messages, logger, () -> ToneColours.DEFAULTS, chime);
    }

    /** Without a {@link Chime}: silent. */
    public CommandGate(
            final LoginRoster roster,
            final CommandAllowlist allowlist,
            final Messages messages,
            final Logger logger,
            final Supplier<ToneColours> colours) {
        this(roster, allowlist, messages, logger, colours, Chime.silent());
    }

    public CommandGate(
            final LoginRoster roster,
            final CommandAllowlist allowlist,
            final Messages messages,
            final Logger logger,
            final Supplier<ToneColours> colours,
            final Chime chime) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.colours = Objects.requireNonNull(colours, "colours");
        this.chime = Objects.requireNonNull(chime, "chime");
    }

    /**
     * Turns "undefined" into "no" for everybody who is not an admin.
     *
     * Reads the roster live, since this fires before the gate fills it. The console keeps Velocity's default.
     */
    @Subscribe
    public void onPermissionsSetup(final PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player player)) {
            return;
        }
        event.setProvider(
                subject -> permission -> roster.isAdmin(player.getUniqueId()) ? Tristate.TRUE : Tristate.FALSE);
    }

    /**
     * Refuses anything the list does not carry.
     *
     * {@code denied()} rather than {@code forwardToServer()}, which would let a backend refuse it a second time.
     */
    @Subscribe
    public void onCommandExecute(final CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (roster.isAdmin(player.getUniqueId()) || allowlist.allows(event.getCommand())) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(Tones.paint(
                MessageRenderer.of(messages)
                        .format(locale(player), MESSAGES.command().unknown()),
                Tone.BAD,
                colours.get()));
        chime.play(player, Feedback.REFUSED);
    }

    /**
     * Removes every root the list does not carry from the tree the client is sent.
     *
     * Names are collected before removing, since {@code getChildren()} is a live view of the node's map.
     */
    @Subscribe
    public void onAvailableCommands(final PlayerAvailableCommandsEvent event) {
        if (roster.isAdmin(event.getPlayer().getUniqueId())) {
            return;
        }
        final List<String> remove = new ArrayList<>();
        for (final CommandNode<?> child : event.getRootNode().getChildren()) {
            if (!allowlist.allowsRoot(child.getName())) {
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

    /** The player's {@code discord_user.locale} from the roster, never the client's setting; English when unknown. */
    private Locale locale(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    /** What was configured, for the startup log line. */
    public CommandAllowlist allowlist() {
        return allowlist;
    }
}
