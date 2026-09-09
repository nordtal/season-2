package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.common.command.AllowlistDirectory;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.Tones;
import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.command.UnknownCommandEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The Paper half of the command allowlist: written once, run by all three backends.
 *
 * <h2>What it is for</h2>
 * The proxy refuses a command before it reaches a backend at all, which is the enforcement that
 * matters. This is the half the proxy cannot do: what the <em>server</em> tells a client exists.
 * {@link PlayerCommandSendEvent} is fired while Paper builds the command tree for one player, and it
 * is the only place a vanilla command can be taken out of it before it is sent. Without it a player
 * still sees {@code /me}, {@code /help}, {@code /trigger} and {@code /list} in their completion, and
 * finds out they are refused only by typing one.
 *
 * <h2>It fails open, on purpose, and says so</h2>
 * The list is published by the proxy into the database and read here. If nothing has ever been
 * published - a fresh deployment, or a backend that started before the proxy - this filter does
 * <b>nothing at all</b> and warns once per read. The alternative, refusing everything until a list
 * arrives, would take every command on the server away for a reason nobody watching could see, and
 * would do it on exactly the day a network is being brought up for the first time. The proxy's own
 * enforcement is neither delayed nor optional, so failing open here loses the completion filter for
 * a few seconds and loses no enforcement.
 *
 * <h2>Two signals, and the poll is the guarantee</h2>
 * The same arrangement as the admin roster, and for the same reasons: {@link #refresh()} re-reads
 * the <em>whole</em> list on the poll, on every notification and on every reconnect, so a lost
 * notification costs latency rather than correctness. The channel is never inspected. Register
 * {@link #refreshes()} and {@link #channels()} with the admin watcher's listener rather than opening
 * a second connection - one connection carrying two channels is cheaper than two and no worse.
 *
 * <h2>The admin check is a cache</h2>
 * {@code AdminWatch#isAdmin}, handed in as a predicate. Not {@code FullServerAdmission}, which only
 * fills its flag when a server is near its cap and would answer "nobody is an admin" for ever on
 * {@code limbo}; and never a query, because {@link PlayerCommandSendEvent} is on the join path.
 */
public final class CommandFilter implements Listener {

    /** Where the published list is read from. An interface so a test needs no database. */
    @FunctionalInterface
    public interface Source {

        /** The current list, or empty when no proxy has ever published one. */
        Optional<CommandAllowlist> read();

        /** The ordinary one: {@code AllowlistDirectory} over the plugin's own pool. */
        static Source of(final AllowlistDirectory directory) {
            Objects.requireNonNull(directory, "directory");
            return directory::published;
        }
    }

    private final Plugin plugin;
    private final Source source;
    private final Predicate<UUID> admin;
    private final PlayerLocales locales;
    private final Messages messages;
    private final Logger logger;

    /**
     * The list as of the last successful read, or {@code null} while none has arrived.
     *
     * <p>Volatile because it is written from the poll thread and the listener thread and read on the
     * main thread, once per command and once per join.</p>
     */
    private volatile CommandAllowlist active;

    /** So that "nothing has been published" is one warning and not one per poll for a season. */
    private volatile boolean warnedAboutMissingList;

    public CommandFilter(final Plugin plugin, final Source source, final Predicate<UUID> admin,
                         final PlayerLocales locales, final Messages messages, final Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.source = Objects.requireNonNull(source, "source");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.locales = Objects.requireNonNull(locales, "locales");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Starts the poll that is the guarantee.
     *
     * <p>Asynchronous, because it is a round trip; and first on the next tick rather than after a
     * whole interval, for the reason {@code AdminWatch#start} gives - the first players through the
     * door would otherwise be handed an unfiltered tree.</p>
     */
    public void start(final Duration pollInterval) {
        final long ticks = Math.max(20L, pollInterval.toSeconds() * 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 1L, ticks);
    }

    /** Hand these to {@code AdminWatch#start}, so the instant path shares its one connection. */
    public List<NotificationListener.Refresh> refreshes() {
        return List.of(new NotificationListener.Refresh("the command allowlist", this::refresh));
    }

    /** Hand these to {@code AdminWatch#start} alongside {@link #refreshes()}. */
    public List<String> channels() {
        return List.of(Channels.ALLOWLIST);
    }

    /**
     * Re-reads the whole list. <b>Never call this on the main thread.</b>
     *
     * <p>A failure leaves the previous list in place rather than clearing it: an unreachable
     * database must not silently open the server up, and it must not close it either. The next tick
     * asks again.</p>
     */
    public void refresh() {
        final Optional<CommandAllowlist> read;
        try {
            read = source.read();
        } catch (final RuntimeException failure) {
            logger.warn("Could not read the command allowlist; the previous one still applies.",
                    failure);
            return;
        }
        if (read.isEmpty()) {
            if (!warnedAboutMissingList) {
                warnedAboutMissingList = true;
                logger.warn("No command allowlist has been published yet, so this server filters"
                        + " nothing: every vanilla command is visible and typeable here. The proxy"
                        + " publishes the list from network.yml when it starts, and it enforces the"
                        + " same list itself in the meantime.");
            }
            return;
        }
        final CommandAllowlist arrived = read.get();
        final CommandAllowlist before = active;
        active = arrived;
        warnedAboutMissingList = false;
        if (!arrived.equals(before)) {
            logger.info("The command allowlist is now: {}", arrived);
        }
    }

    /**
     * Refuses a command the list does not carry.
     *
     * <p>{@link EventPriority#LOWEST} so the decision is taken before anything else acts on the
     * line, and the event is cancelled rather than rewritten - a rewritten command is a command
     * somebody else's listener has already seen.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        final CommandAllowlist current = active;
        if (current == null || admin.test(event.getPlayer().getUniqueId())
                || current.allows(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(refusal(event.getPlayer().getUniqueId()));
    }

    /**
     * Answers a command that does not exist with the same line a refused one gets.
     *
     * <h2>Why the same line, and why it has to be said twice</h2>
     * <b>There is exactly one sentence for "that does not exist" and "you may not type that."</b>
     * That is the whole point of the allowlist's wording: a player who learns which of the two they
     * hit has learned what exists on this server, which is what the list is keeping from them. Two
     * sentences saying one thing is also the kind of seam nobody notices - both are correct, both
     * are translated, and they only ever appear one at a time.
     *
     * <p>It is said twice because two different things produce it. {@link #onCommand} answers a
     * command that <em>does</em> exist and is not on the list. This answers one Paper cannot find at
     * all - which is what a genuine typo is, what an <b>admin</b> gets (they skip the filter
     * entirely), and what <b>everybody</b> gets while no allowlist has been published yet. Without
     * this handler each of those reads vanilla's "Unknown or incomplete command, see below for
     * error" with a red caret under the offending character, in the server's language.</p>
     *
     * <h2>The console keeps vanilla's</h2>
     * Deliberately. Paper's default text carries the parse position, which is diagnosis rather than
     * decoration, and the console is an operator reading a log next to a stack trace - not somebody
     * who has to be kept from enumerating the command tree. They already have the whole of it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnknownCommand(final UnknownCommandEvent event) {
        if (!(event.getSender() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        event.message(refusal(player.getUniqueId()));
    }

    /**
     * The one refusal line, in that player's own language.
     *
     * <p>{@code discord_user.locale} through {@link PlayerLocales}, never the client's setting;
     * {@code of(...)} answers English until the join lookup lands, which is the whole reason it
     * exists.</p>
     */
    private net.kyori.adventure.text.Component refusal(final UUID player) {
        return Tones.paint(MessageRenderer.of(messages).get(locales.of(player), "command.unknown"),
                Tone.BAD);
    }

    /**
     * Takes every command the list does not carry out of the tree this player is sent.
     *
     * <p>{@code getCommands()} is a mutable collection of the labels Paper is about to send,
     * namespaced forms included. Removing a label removes it from tab completion and from the
     * client's syntax hints; it does not stop the command being typed, which is what
     * {@link #onCommand} is for.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandSend(final PlayerCommandSendEvent event) {
        final CommandAllowlist current = active;
        if (current == null || admin.test(event.getPlayer().getUniqueId())) {
            return;
        }
        event.getCommands().removeIf(label -> !current.allowsRoot(label));
    }
}
