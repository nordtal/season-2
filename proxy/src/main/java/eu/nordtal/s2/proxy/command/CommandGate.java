package eu.nordtal.s2.proxy.command;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.common.message.Tones;
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
 * <h2>The hole this closes</h2>
 * Velocity's {@code /server} is open to every player. Its permission node refuses only on an
 * explicit {@link Tristate#FALSE} and nothing in this network ever set one, so {@code /server
 * hunger-games} during the SMP phase put a player there - past the phase, past the access check on
 * that phase's backend, and past the resource pack. On the backends the same gap shows from the
 * other side: {@code /me}, {@code /help}, {@code /trigger}, {@code /list} and {@code /tell} are all
 * offered to everybody, and this season has an answer for none of them.
 *
 * <h2>Three events, because there are three ways past</h2>
 * <ul>
 *   <li>{@link PermissionsSetupEvent} - Velocity's own commands ask a permission function. A
 *       non-admin's answers {@code FALSE} to everything, which is the only value that actually
 *       refuses; {@code UNDEFINED}, the default, is what let {@code /server} through.</li>
 *   <li>{@link CommandExecuteEvent} - the execution itself, for anything typed regardless of what
 *       the client believes exists. This is the one that matters: the other two shape what is
 *       offered, and a player can always type a command they were not offered.</li>
 *   <li>{@link PlayerAvailableCommandsEvent} - the tree the client is sent, which is where tab
 *       completion and the grey syntax hints come from. It carries the <em>backend's</em> commands
 *       as well as the proxy's, so this is also what stops a Paper server advertising
 *       {@code /trigger} before that server's own filter has been asked anything.</li>
 * </ul>
 *
 * <h2>A refused command answers exactly like a mistyped one</h2>
 * "That command does not exist" (Till, 2026-09-08), one key for both cases. Telling somebody they
 * are not allowed to run {@code /server} is telling them that {@code /server} exists; the whole
 * value of a list of what is allowed is that nothing outside it is discoverable, and a distinct
 * refusal message would hand that back.
 *
 * <h2>The admin check is a cache and never a query</h2>
 * {@link LoginRoster}, the same map {@code VelocityCommands} gates its Brigadier trees on. All three
 * of these events sit on the connection path or the command path; none of them is a place for a
 * blocking round trip. An admin whose flag was revoked loses it here on the roster's next refresh,
 * which is the proxy's poll and its {@code LISTEN} - the same two signals as everything else.
 */
public final class CommandGate {

    /**
     * How a refusal sounds on the proxy - the Velocity-side twin of {@code PaperUser.Chime}, kept
     * as its own interface because a Velocity {@link Player} is not a Bukkit one and this module
     * must not depend on {@code paper-common} to borrow its shape.
     *
     * <h2>The proxy can reach a player on any backend, measured 2026-09-15 (season-2-ingame/13)</h2>
     * Measured before writing anything: {@link Player} extends {@code CommandSource}, which extends
     * {@code net.kyori.adventure.audience.Audience} - the same interface {@code sendMessage} comes
     * from - and {@link Player} itself carries a default {@code playSound(Sound)} straight from
     * Adventure. {@code RestartWatch}, already running in production, calls {@code sendMessage} and
     * {@code showTitle} from this exact proxy process on players standing on any backend; nothing in
     * that call path differs for {@code playSound}, because Velocity holds the client connection
     * itself; a backend is not in between. So the answer to season-2-ingame/05's open question is
     * <b>yes, the API says the proxy can</b> - this is a structural finding, not an acoustic one:
     * nobody has listened, and that stays {@code Owner: till} until somebody does.
     *
     * <h2>A real chime, wired in season-2-ingame/28</h2>
     * {@code common}'s {@code SoundVocabularyTest} scans {@code proxy} (this module is on
     * its list) and refuses a bare {@code playSound(} or {@code net.kyori.adventure.sound.} outside
     * a named, allow-listed "Sounds" adapter file. {@code ProxySounds} in
     * {@code eu.nordtal.s2.proxy.feedback} is that adapter for this module, allow-listed
     * alongside {@code SmpSounds.java} and {@code HungerGamesSounds.java}, and
     * {@code ProxyPlugin} hands one to the constructor below. {@link #silent()} still
     * exists for a caller with no chime of its own - the tests in this package use it deliberately,
     * to hold the old, unchanged constructors in place.
     */
    @FunctionalInterface
    public interface Chime {

        void play(Player player, Feedback feedback);

        /** For as long as nothing plays a real sound here - see the class javadoc above. */
        static Chime silent() {
            return (player, feedback) -> {};
        }
    }

    private final LoginRoster roster;
    private final Messages messages;
    private final Logger logger;
    private final Supplier<ToneColours> colours;
    private final Chime chime;

    /**
     * Read straight out of {@code network.yml} rather than out of the database.
     *
     * <p>The proxy publishes the list for the three backends and does not read it back: it holds
     * the file, so a round trip would only introduce a way for the process that owns the truth to
     * disagree with it. It is final for the same reason {@code network.yml} has no reload command -
     * see {@code NetworkSpec}.</p>
     */
    private final CommandAllowlist allowlist;

    /** Without a {@link Chime}: silent, which is every existing caller's behaviour unchanged. */
    public CommandGate(
            final LoginRoster roster, final CommandAllowlist allowlist, final Messages messages, final Logger logger) {
        this(roster, allowlist, messages, logger, Chime.silent());
    }

    /**
     * Without a colour supplier: {@link ToneColours#DEFAULTS}, which is every existing caller's
     * behaviour unchanged (season-2-ingame/22) - none of them painted anything but the defaults
     * before this ticket.
     */
    public CommandGate(
            final LoginRoster roster,
            final CommandAllowlist allowlist,
            final Messages messages,
            final Logger logger,
            final Chime chime) {
        this(roster, allowlist, messages, logger, () -> ToneColours.DEFAULTS, chime);
    }

    /** Without a {@link Chime}: silent - the real caller, {@code ProxyPlugin}, has none yet. */
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
     * <p>The function is built once per subject and consulted every time, so it reads the roster
     * live rather than closing over an answer: this event fires <em>before</em> the login gate has
     * filled the roster, and a snapshot taken here would say "not an admin" for the whole
     * session.</p>
     *
     * <p>The console is left on Velocity's default. It is the operator, it already holds every
     * command through {@code Surface.CONSOLE}, and refusing it a permission would take away the one
     * path that still works when the database holds no admin at all.</p>
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
     * <p>{@code denied()} rather than {@code forwardToServer()}: forwarding would hand the command
     * to a Paper server, where our own filter would refuse it a second time and the player would be
     * told twice. The one case where denying costs something is a command the client signed
     * arguments for - Velocity disconnects the player rather than dropping it - and that can only
     * happen for a command the client's tree declared as signable, which is a command
     * {@link #onAvailableCommands} has already removed. It is named here because it is the one
     * failure mode of this method that only a real client can show.</p>
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
     * <p>Root labels only, which is all this event can express and all it needs to: a subcommand an
     * admin may run and a player may not is already absent, because Velocity filters the tree by
     * each node's {@code requires} before it gets here. What is left to remove is whole commands -
     * Velocity's own and the backend's.</p>
     *
     * <p>The names are collected before anything is removed. {@code getChildren()} is a view over
     * the node's own map, and removing while walking it is a
     * {@link java.util.ConcurrentModificationException} waiting for the first player who has more
     * than one disallowed command - which is every player.</p>
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

    /**
     * Their language as the login query read it - {@code discord_user.locale}, never the Minecraft
     * client's own setting, which docs/i18n.md forbids by name. English for anybody the roster has
     * lost track of, which is the fallback everywhere in this repository.
     */
    private Locale locale(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    /** What was configured, for the startup log line. */
    public CommandAllowlist allowlist() {
        return allowlist;
    }
}
