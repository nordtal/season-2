package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.CommandInbox;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.database.notify.Channels;
import eu.nordtal.s2.database.notify.NotificationListener;
import eu.nordtal.s2.messages.Messages;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * A Paper plugin's end of the command channel: the inbox, its poll, and its wake-up.
 *
 * It renders with the markup-free {@code :commands} bundle, since the same answer may reach Discord.
 */
public final class PaperCommandInbox {

    /** How often the inbox looks, when no notification woke it. */
    public static final Duration POLL = Duration.ofSeconds(5);

    private final CommandInbox inbox;
    private Messages messages;

    /**
     * @param here     which process this is
     * @param requests the shared table
     * @param access   re-reads the admin flag after a row is claimed, since it can change while a request waits
     */
    public PaperCommandInbox(
            final Plugin plugin, final Target here, final CommandRequests requests, final AccessReader access) {
        this(plugin, here, requests, access, sharedBundle(plugin));
    }

    /** The same, with a bundle the plugin already built so that it can reload it. */
    public PaperCommandInbox(
            final Plugin plugin,
            final Target here,
            final CommandRequests requests,
            final AccessReader access,
            final Messages shared) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(access, "access");
        this.messages = Objects.requireNonNull(shared, "shared");
        this.inbox = new CommandInbox(
                here,
                requests,
                shared,
                CommandInbox.AdminCheck.of(access::admins, access::adminMinecraftAccounts),
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    /**
     * Loads the shared command bundle with the operator's override, and no other layer.
     *
     * Reload it with the plugin's own; its unknown override keys belong to the module, so do not report them.
     */
    public static Messages sharedBundle(final Plugin plugin) {
        return Messages.load(
                plugin.getClass().getClassLoader(),
                "messages/commands",
                plugin.getDataFolder().toPath().resolve("messages"),
                java.util.Locale.ENGLISH,
                java.util.Locale.GERMAN);
    }

    /**
     * Re-reads the shared bundle and its override, next to the plugin's own reload.
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

    /** Makes a command runnable here; its effects must run their work inline. */
    public <E extends CommandEffects> PaperCommandInbox register(final NordtalCommand<E> command, final E effects) {
        inbox.register(command, effects);
        return this;
    }

    /** Starts looking, always async, since commands ask for the main thread themselves. */
    public void start(final Plugin plugin) {
        final long ticks = Math.max(20L, POLL.toSeconds() * 20L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, inbox::drain, ticks, ticks);
        plugin.getLogger()
                .info("the command inbox is listening for " + inbox.size() + " command(s) from other processes");
    }

    /** Returns the wake-up, for {@link eu.nordtal.s2.papercommon.access.AdminWatch}'s listener. */
    public List<NotificationListener.Refresh> refreshes() {
        return List.of(new NotificationListener.Refresh("the command inbox", inbox::drain));
    }

    /** Returns the channel that wake-up listens on. */
    public List<String> channels() {
        return List.of(Channels.COMMAND);
    }

    /** Returns how many commands this process can be asked to run. */
    public int size() {
        return inbox.size();
    }
}
