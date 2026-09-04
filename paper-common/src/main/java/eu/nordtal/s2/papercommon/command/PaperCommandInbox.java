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

    /**
     * @param here     which process this is
     * @param requests the shared table
     * @param access   how the admin flag is re-read after a row is claimed - which is a second check
     *                 and not a duplicate, because the flag can change while a request waits
     */
    public PaperCommandInbox(final Plugin plugin, final Target here,
                             final CommandRequests requests, final AccessDirectory access) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(access, "access");
        this.inbox = new CommandInbox(here, requests, sharedBundle(plugin),
                request -> isStillAdmin(access, request.discordId().orElse(null),
                        request.minecraftId().orElse(null)),
                (message, failure) -> plugin.getLogger()
                        .log(java.util.logging.Level.WARNING, message, failure));
    }

    /**
     * The shared command bundle, alone.
     *
     * <p>Loaded off the plugin's own class loader because {@code :commands} is shaded into it - so
     * this is the copy that shipped with this build, and a version skew shows up as an unknown key
     * rather than as a message from another release.</p>
     */
    private static Messages sharedBundle(final Plugin plugin) {
        return Messages.load(plugin.getClass().getClassLoader(), "messages/commands",
                java.util.Locale.ENGLISH, java.util.Locale.GERMAN);
    }

    /**
     * Whether whoever wrote this row is an admin <em>now</em>.
     *
     * <p>By Discord id when there is one, because that is what {@code discord_user.admin} is keyed
     * on. The Minecraft UUID is the fallback for a request written by a game surface that had no
     * link to hand, and it goes through the same set - {@code adminMinecraftAccounts} is the join
     * the proxy's roster already uses.</p>
     */
    private static boolean isStillAdmin(final AccessDirectory access, final String discordId,
                                        final UUID minecraftId) {
        if (discordId != null) {
            return access.admins().contains(discordId);
        }
        if (minecraftId != null) {
            final Set<UUID> admins = access.adminMinecraftAccounts();
            return admins.contains(minecraftId);
        }
        // Neither identity: the console, which is the operator by definition. A row can only say
        // CONSOLE if an adapter on a machine somebody already has a shell on wrote it.
        return true;
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
