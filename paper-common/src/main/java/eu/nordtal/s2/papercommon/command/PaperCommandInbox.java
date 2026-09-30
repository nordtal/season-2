package eu.nordtal.s2.papercommon.command;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.CommandInbox;
import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

/**
 * A Paper plugin's end of the command channel: the inbox and its wake-up on the process's signal hub.
 *
 * It renders with the markup-free {@code :commands} bundle, since the same answer may reach Discord.
 */
public final class PaperCommandInbox {

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
                        Languages.NETWORK.locales())
                .within(MessageEnvironment.of(plugin.getName()));
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

    /** Drains on every signal of {@code signals}, off the main thread, since commands ask for it themselves. */
    public void listen(final SignalHub signals, final Plugin plugin) {
        signals.on(Channel.COMMAND, "the command inbox", inbox::drain);
        plugin.getLogger()
                .info("the command inbox is listening for " + inbox.size() + " command(s) from other processes");
    }

    /** Returns how many commands this process can be asked to run. */
    public int size() {
        return inbox.size();
    }
}
