package eu.nordtal.s2.papercommon.access;

import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AdminOperators;
import eu.nordtal.s2.database.access.FullServerAdmission;
import eu.nordtal.s2.database.notify.Channels;
import eu.nordtal.s2.database.notify.NotificationListener;
import eu.nordtal.s2.database.notify.Notifications;
import eu.nordtal.s2.database.notify.PostgresNotifications;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps a Paper server's operators in step with {@code discord_user.admin} while people are online.
 *
 * The poll is the guarantee and the listener only makes it instant; reads run async, the apply on the main thread.
 */
public final class AdminWatch implements AutoCloseable {

    private final Plugin plugin;
    private final AccessDirectory access;
    private final AdminOperators operators;
    private final FullServerAdmission admission;
    private final Consumer<Set<UUID>> also;
    private final Logger logger;

    private volatile @Nullable NotificationListener listener;

    /** The admin set as of the last refresh, read by Brigadier's {@code requires} on the main thread. */
    private volatile Set<UUID> known = Set.of();

    private volatile boolean running = true;

    /**
     * @param plugin    the owning plugin, for the scheduler
     * @param access    where the admin set is read from
     * @param operators what grants and removes operator
     * @param admission the full-server exemption, kept in step so a revoked admin loses it
     * @param also      anything else this plugin caches about admins, applied on the main thread; {@code set -> { }}
     *     when none
     * @param logger    the plugin logger
     */
    public AdminWatch(
            final Plugin plugin,
            final AccessDirectory access,
            final AdminOperators operators,
            final FullServerAdmission admission,
            final Consumer<Set<UUID>> also,
            final Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.access = Objects.requireNonNull(access, "access");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.also = Objects.requireNonNull(also, "also");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Starts the poll, and the listener when one was asked for.
     *
     * @param pollInterval  how often to re-read regardless; this is the guarantee
     * @param listenOn      {@code null} to run on the poll alone, else the database to {@code LISTEN} on
     */
    public void start(final Duration pollInterval, final @Nullable DatabaseConnection listenOn) {
        start(pollInterval, listenOn, List.of(), List.of());
    }

    /**
     * Starts the same, plus somebody else's channels on the same connection.
     *
     * @param alsoRefresh  extra work to do on every signal and on every reconnect
     * @param alsoChannels extra channels to listen on; ignored when {@code listenOn} is null
     */
    public void start(
            final Duration pollInterval,
            final @Nullable DatabaseConnection listenOn,
            final List<NotificationListener.Refresh> alsoRefresh,
            final List<String> alsoChannels) {
        final long ticks = Math.max(20L, pollInterval.toSeconds() * 20L);
        // First run on the next tick, not after a whole interval, so isAdmin does not answer "nobody" at first.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 1L, ticks);

        if (listenOn == null) {
            logger.info(
                    "The {} LISTEN connection is disabled; the {}s poll is the only path an" + " admin change travels",
                    Channels.ADMIN,
                    pollInterval.toSeconds());
            return;
        }

        final List<String> channels = new java.util.ArrayList<>(List.of(Channels.ADMIN));
        channels.addAll(alsoChannels);

        final Notifications.Connector connector = PostgresNotifications.connector(
                listenOn.jdbcUrl(),
                listenOn.username(),
                listenOn.password(),
                listenOn.socketTimeoutSeconds(),
                plugin.getName() + "-admin-listener",
                channels);

        final List<NotificationListener.Refresh> refreshes =
                new java.util.ArrayList<>(List.of(new NotificationListener.Refresh("the admin roster", this::refresh)));
        refreshes.addAll(alsoRefresh);

        final NotificationListener started = new NotificationListener(
                connector, plugin.getName() + "-admin-listener", refreshes, logger, pollInterval);
        this.listener = started;
        started.start();
    }

    /**
     * Returns whether this account was an admin as of the last refresh.
     *
     * A set lookup; it answers {@code false} until the first read, so an unreachable database hands out no admin.
     */
    public boolean isAdmin(final UUID mcUuid) {
        return known.contains(mcUuid);
    }

    /** Reads the admin set and applies it; never call this on the main thread. */
    public void refresh() {
        if (!running) {
            return;
        }
        final Set<UUID> admins;
        try {
            admins = access.adminMinecraftAccounts();
        } catch (final RuntimeException failure) {
            // Not fatal: an unreachable database must not cost operators who already hold their flag.
            logger.warn("Could not read the admin roster; operators are unchanged until the next" + " poll.", failure);
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, () -> apply(admins));
        } catch (final IllegalPluginAccessException shuttingDown) {
            // The plugin was disabled between the read and the hop; the next enable sweep fixes it.
            logger.debug("Dropped an admin refresh because the plugin is no longer enabled");
        }
    }

    /** Applies the set on the main thread: who is online, who of them is an admin, and what changes. */
    private void apply(final Set<UUID> admins) {
        // Copied once: `known` is what isAdmin, and therefore Brigadier's requires, answers from.
        final Set<UUID> snapshot = Set.copyOf(admins);
        known = snapshot;
        final Set<UUID> online = new HashSet<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }

        final Set<UUID> before = operators.held();
        operators.refresh(snapshot, online);
        final Set<UUID> after = operators.held();

        for (final UUID player : online) {
            admission.remember(player, snapshot.contains(player));
        }
        also.accept(snapshot);

        if (!before.equals(after)) {
            final Set<UUID> gained = new HashSet<>(after);
            gained.removeAll(before);
            final Set<UUID> lost = new HashSet<>(before);
            lost.removeAll(after);
            logger.info("The admin roster changed: {} gained operator, {} lost it", gained.size(), lost.size());
        }
    }

    @Override
    public void close() {
        running = false;
        final NotificationListener open = this.listener;
        if (open != null) {
            open.close();
        }
    }

    /** What {@link PostgresNotifications} needs to open a connection, as values each plugin's own spec reduces to. */
    public record DatabaseConnection(String jdbcUrl, String username, String password, int socketTimeoutSeconds) {

        public DatabaseConnection {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(username, "username");
        }
    }
}
