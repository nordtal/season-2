package eu.nordtal.s2.papercommon.access;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.common.notify.Notifications;
import eu.nordtal.s2.common.notify.PostgresNotifications;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Keeps a Paper server's operators in step with {@code discord_user.admin} while people are online.
 *
 * <h2>What was missing before this, and why it mattered</h2>
 * {@code AdminOperators} has always granted operator at join and removed it at quit, so the flag was
 * read exactly once per session. An admin whose Discord role was taken away therefore kept operator
 * on all three backends <b>until they chose to disconnect</b> - and an emergency revocation is
 * precisely the case where waiting for somebody to log off is the wrong direction. The proxy had
 * already learned this once and fixed it on 2026-09-02 for its own {@code LoginRoster}; the
 * backends, where operator actually is, had not.
 *
 * <p>{@code AdminOperators#refresh} was written for this on 2026-09-04 and then had no caller for a
 * day, which is the worst of the three possible states: a mechanism that exists, is tested, and does
 * nothing. This class is its caller.</p>
 *
 * <h2>Two signals, one of which is the guarantee</h2>
 * The poll is the guarantee. {@code LISTEN nordtal_admin} only makes a revocation feel instant, and
 * it can be turned off in every plugin's {@code config.yml} without changing what is true - the same
 * arrangement, and the same reasoning, as the proxy's phase watch. A notification is never trusted
 * as state either: both paths re-read the whole admin set through
 * {@link AccessDirectory#adminMinecraftAccounts()}, so a lost notification costs latency rather than
 * correctness and needs no bookkeeping to catch up on.
 *
 * <h2>Which thread does what, and why it is split</h2>
 * The read is a database round trip and never happens on the main thread - the rule this repository
 * has had without exception since 2026-09-01. The write is {@code ops.json} through Bukkit's own op
 * list, which is main-thread state, so the apply hops back. That is the whole of the split: read
 * where waiting is allowed, write where the server lives.
 *
 * <p>The cost of the hop is that a refresh is not atomic - somebody can join between the read and
 * the apply. They are simply not in {@code online} when the apply runs, and their own join handler
 * has already asked the database for their flag directly, so the answer they get is newer than the
 * one this refresh carried.</p>
 *
 * <h2>What a poll tick costs when nothing changed</h2>
 * One indexed query and nothing else. {@code AdminOperators#refresh} writes only on a change, which
 * is what keeps {@code ops.json} from being rewritten every thirty seconds for as long as the server
 * runs, and is asserted by {@code AdminOperatorsTest#repeatedRefreshIsFree}.
 */
public final class AdminWatch implements AutoCloseable {

    private final Plugin plugin;
    private final AccessDirectory access;
    private final AdminOperators operators;
    private final FullServerAdmission admission;
    private final Consumer<Set<UUID>> also;
    private final Logger logger;

    private volatile NotificationListener listener;

    /**
     * The admin set as of the last refresh, for anything that needs the answer without waiting.
     *
     * <p>{@link #isAdmin} is what Brigadier's {@code requires} predicate reads, and that predicate
     * runs on the main thread while a client's command tree is built - so it has to be a set
     * lookup and can never be a query. It is the same set the operator grant is applied from, so a
     * command tree and {@code ops.json} cannot disagree about who is an admin.</p>
     */
    private volatile Set<UUID> known = Set.of();
    private volatile boolean running = true;

    /**
     * @param plugin    the owning plugin, for the scheduler
     * @param access    where the admin set is read from
     * @param operators what grants and removes operator
     * @param admission the full-server exemption, kept in step so a revoked admin does not keep a
     *                  warmed "let them in anyway" answer from an earlier login
     * @param also      anything else this plugin caches about admins, applied on the main thread
     *                  with the fresh set. {@code smp} passes its {@code Identities} update here,
     *                  because the admin tag on a nametag is drawn from that cache and would
     *                  otherwise go on claiming somebody is an admin after they stopped being one.
     *                  Pass {@code set -> { }} when there is nothing.
     * @param logger    the plugin logger
     */
    public AdminWatch(final Plugin plugin, final AccessDirectory access,
                      final AdminOperators operators, final FullServerAdmission admission,
                      final Consumer<Set<UUID>> also, final Logger logger) {
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
     * @param pollInterval  how often to re-read regardless. This is the guarantee; the listener is
     *                      not
     * @param listenOn      {@code null} to run on the poll alone. Otherwise the database to open a
     *                      dedicated {@code LISTEN nordtal_admin} connection against
     */
    public void start(final Duration pollInterval, final DatabaseConnection listenOn) {
        start(pollInterval, listenOn, List.of(), List.of());
    }

    /**
     * The same, plus somebody else's channels on the same connection.
     *
     * <h2>Why they share one</h2>
     * {@link NotificationListener} was built for exactly this: it takes several channels and several
     * refreshes, never inspects which channel woke it, and runs every refresh on every signal. So
     * one connection carrying two channels is cheaper than two connections and no worse - and the
     * reconnect loop, the liveness check and the "re-read in full on every reconnect" rule are all
     * written once instead of twice.
     *
     * <p>The command inbox is the second caller. Its own poll is separate and much shorter than the
     * admin poll, because a command typed in Discord should not wait half a minute when a
     * notification is missed; this only gives it the instant path.</p>
     *
     * @param alsoRefresh  extra work to do on every signal and on every reconnect
     * @param alsoChannels extra channels to listen on. Ignored when {@code listenOn} is null - the
     *                     caller's own poll is then the only path, which is the same trade the
     *                     admin roster makes
     */
    public void start(final Duration pollInterval, final DatabaseConnection listenOn,
                      final List<NotificationListener.Refresh> alsoRefresh,
                      final List<String> alsoChannels) {
        final long ticks = Math.max(20L, pollInterval.toSeconds() * 20L);
        // First run on the next tick rather than after a whole interval. The set starts empty, and
        // anything reading it through isAdmin - a command tree, most of all - would answer "nobody
        // is an admin" for the first thirty seconds of the server's life otherwise.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 1L, ticks);

        if (listenOn == null) {
            logger.info("The {} LISTEN connection is disabled; the {}s poll is the only path an"
                    + " admin change travels", Channels.ADMIN, pollInterval.toSeconds());
            return;
        }

        final List<String> channels = new java.util.ArrayList<>(List.of(Channels.ADMIN));
        channels.addAll(alsoChannels);

        final Notifications.Connector connector = PostgresNotifications.connector(
                listenOn.jdbcUrl(), listenOn.username(), listenOn.password(),
                listenOn.socketTimeoutSeconds(),
                plugin.getName() + "-admin-listener", channels);

        final List<NotificationListener.Refresh> refreshes =
                new java.util.ArrayList<>(List.of(
                        new NotificationListener.Refresh("the admin roster", this::refresh)));
        refreshes.addAll(alsoRefresh);

        final NotificationListener started = new NotificationListener(connector,
                plugin.getName() + "-admin-listener", refreshes, logger, pollInterval);
        this.listener = started;
        started.start();
    }

    /**
     * Whether this account was an admin as of the last refresh.
     *
     * <p>A set lookup, never a query - see {@link #known}. It answers {@code false} for the first
     * tick of the server's life and for as long as the database cannot be read, which is the
     * correct direction to fail in: an unreachable database must not hand out admin.</p>
     */
    public boolean isAdmin(final UUID mcUuid) {
        return known.contains(mcUuid);
    }

    /**
     * Reads the admin set and applies it. <b>Never call this on the main thread.</b>
     *
     * <p>Public so a reload command or a drill can force one without waiting for the poll.</p>
     */
    public void refresh() {
        if (!running) {
            return;
        }
        final Set<UUID> admins;
        try {
            admins = access.adminMinecraftAccounts();
        } catch (final RuntimeException failure) {
            // Deliberately not fatal. An unreachable database must not cost the operators who
            // already hold their flag - the next tick asks again, and the enable sweep is what
            // guarantees nothing survives a restart.
            logger.warn("Could not read the admin roster; operators are unchanged until the next"
                    + " poll.", failure);
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, () -> apply(admins));
        } catch (final IllegalPluginAccessException shuttingDown) {
            // The plugin was disabled between the read on this thread and the hop to the main one -
            // which is the ordinary shape of a shutdown, because the listener thread is a daemon
            // that outlives disable by a few milliseconds. Nothing to do and nothing wrong: the
            // enable sweep on the next start removes every operator regardless.
            logger.debug("Dropped an admin refresh because the plugin is no longer enabled");
        }
    }

    /** The main-thread half: who is online, who of them is an admin, and what changes. */
    private void apply(final Set<UUID> admins) {
        known = admins;
        final Set<UUID> online = new HashSet<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }

        final Set<UUID> before = operators.held();
        operators.refresh(admins, online);
        final Set<UUID> after = operators.held();

        for (final UUID player : online) {
            admission.remember(player, admins.contains(player));
        }
        also.accept(admins);

        if (!before.equals(after)) {
            final Set<UUID> gained = new HashSet<>(after);
            gained.removeAll(before);
            final Set<UUID> lost = new HashSet<>(before);
            lost.removeAll(after);
            logger.info("The admin roster changed: {} gained operator, {} lost it",
                    gained.size(), lost.size());
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

    /**
     * What {@link PostgresNotifications} needs to open a connection, taken as values.
     *
     * <p>Each of the three plugins describes its database in its own {@code database.yml} spec
     * interface, and those are three unrelated types carrying the same four fields. This is the
     * shape they all reduce to - the same reason {@code AccessDirectory}'s factories take a
     * {@code DataSource} rather than any one module's config.</p>
     */
    public record DatabaseConnection(String jdbcUrl, String username, String password,
                                     int socketTimeoutSeconds) {

        public DatabaseConnection {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(username, "username");
        }
    }
}
