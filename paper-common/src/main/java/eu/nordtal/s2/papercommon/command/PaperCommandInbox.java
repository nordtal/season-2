package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.command.CommandRequests;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.commands.remote.CommandInbox;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A Paper plugin's end of the command channel: the inbox, its poll, and its wake-up.
 *
 * <h2>Why the bundle it renders with is not the plugin's</h2>
 * It is {@code :commands}' own, loaded on its own. The plugin's layered {@code Messages} would let
 * the module's bundle win, and the module's bundle is allowed MiniMessage - which would reach a
 * Discord admin as a literal {@code <green>}. The shared bundle carries no markup at all, precisely
 * so that one string can be correct on both surfaces, and this is the place that depends on it.
 *
 * <h2>The poll here is short, and the admin roster's is not</h2>
 * Thirty seconds is fine for "an admin was revoked" because the notification is the normal path and
 * the poll is the safety net. It is not fine for a command: the asker gives up after thirty seconds,
 * so a missed notification would mean the command is answered exactly when nobody is listening any
 * more. Hence a few seconds here, on Bukkit's own scheduler, which costs a query against a partial
 * index on a table that is empty almost all of the time.
 */
public final class PaperCommandInbox {

    /** How often the inbox looks, when no notification woke it. */
    public static final Duration POLL = Duration.ofSeconds(5);

    private final CommandInbox inbox;
    private Messages messages;

    /**
     * @param here     which process this is
     * @param requests the shared table
     * @param access   how the admin flag is re-read after a row is claimed - which is a second check
     *                 and not a duplicate, because the flag can change while a request waits
     */
    public PaperCommandInbox(final Plugin plugin, final Target here,
                             final CommandRequests requests, final AccessDirectory access) {
        this(plugin, here, requests, access, sharedBundle(plugin));
    }

    /** The same, with a bundle the plugin already built so that it can reload it. */
    public PaperCommandInbox(final Plugin plugin, final Target here,
                             final CommandRequests requests, final AccessDirectory access,
                             final Messages shared) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(access, "access");
        this.messages = Objects.requireNonNull(shared, "shared");
        this.inbox = new CommandInbox(here, requests, shared,
                CommandInbox.AdminCheck.of(access::admins, access::adminMinecraftAccounts),
                (message, failure) -> plugin.getLogger()
                        .log(java.util.logging.Level.WARNING, message, failure));
    }

    /**
     * The shared command bundle, alone - and the operator's override on top of it.
     *
     * <p>Loaded off the plugin's own class loader because {@code :commands} is shaded into it - so
     * this is the copy that shipped with this build, and a version skew shows up as an unknown key
     * rather than as a message from another release.</p>
     *
     * <p><b>Alone means one root, not no overrides.</b> The layering is what has to be avoided here,
     * because the module's own bundle is allowed MiniMessage; the override directory is the
     * operator's single lever over wording, and until 2026-09-05 it reached the answer a command
     * gave in chat and not the one it gave to a Discord admin - the same command, reading
     * differently depending on where it was typed.</p>
     *
     * <p>The plugin should keep what this returns and reload it wherever it reloads its own, which
     * is what {@link #reloadMessages()} is for. It should <em>not</em> report this bundle's unknown
     * override keys: a key only the module declares is not unknown, it is simply in the other
     * bundle, and the plugin's layered {@code Messages} already names the genuinely unknown ones.</p>
     */
    public static Messages sharedBundle(final Plugin plugin) {
        return Messages.load(plugin.getClass().getClassLoader(), "messages/commands",
                plugin.getDataFolder().toPath().resolve("messages"),
                java.util.Locale.ENGLISH, java.util.Locale.GERMAN);
    }

    /**
     * Re-read the shared bundle and its override.
     *
     * <p>For the plugin's own reload command to call next to its own reload. Without it the answers
     * this inbox writes back keep the wording the process started with, for as long as it runs.</p>
     *
     * @return whether the running wording is now what the files say
     */
    public boolean reloadMessages() {
        try {
            messages.reload();
            return true;
        } catch (final RuntimeException failure) {
            return false;
        }
    }


    /** Make a command runnable here. Effects must run their work inline - the inbox checks. */
    public <E extends CommandEffects> PaperCommandInbox register(final NordtalCommand<E> command,
                                                                 final E effects) {
        inbox.register(command, effects);
        return this;
    }

    /**
     * Start looking.
     *
     * <p>Async, always: {@link CommandInbox#drain()} claims rows and runs commands, and the whole
     * point of the effects layer is that whatever needs the main thread asks for it itself.</p>
     */
    public void start(final Plugin plugin) {
        final long ticks = Math.max(20L, POLL.toSeconds() * 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, inbox::drain, ticks, ticks);
        plugin.getLogger().info("the command inbox is listening for " + inbox.size()
                + " command(s) from other processes");
    }

    /** The wake-up, to hand to {@link eu.nordtal.s2.papercommon.access.AdminWatch}'s listener. */
    public List<NotificationListener.Refresh> refreshes() {
        return List.of(new NotificationListener.Refresh("the command inbox", inbox::drain));
    }

    /** The channel that wake-up listens on. */
    public List<String> channels() {
        return List.of(Channels.COMMAND);
    }

    /** How many commands this process can be asked to run. */
    public int size() {
        return inbox.size();
    }
}
