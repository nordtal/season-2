package eu.nordtal.s2.limbo;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.command.AllowlistDirectory;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.limbo.LimboProtocol;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.limbo.command.BukkitLimboEffects;
import eu.nordtal.s2.limbo.command.LimboCommand;
import eu.nordtal.s2.limbo.config.ColoursSpec;
import eu.nordtal.s2.limbo.config.Configs;
import eu.nordtal.s2.limbo.config.DatabaseSpec;
import eu.nordtal.s2.limbo.config.LimboSpec;
import eu.nordtal.s2.limbo.db.LimboPool;
import eu.nordtal.s2.limbo.listener.FullServerGate;
import eu.nordtal.s2.limbo.listener.PresenceListener;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.papercommon.command.CommandFilter;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import java.util.Locale;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The season 2 waiting room. Every login lands here first, whatever the phase, and leaves when the proxy says so.
 *
 * What it shows is <b>nothing</b>: black, no visible world, no other players and no chat. A title in the player's
 * language says what they are waiting for, and that is the entire interface.
 *
 * The three things that end a wait - the pack is applied, the phase's backend is up, maintenance is over - are all
 * the proxy's to know and all arrive as a {@code nordtal:limbo} plugin message ( {@link LimboProtocol}). This
 * plugin's only outgoing message says "this player has arrived"; it never says where anybody should go, because
 * routing lives in one process and this is not it.
 *
 * The database connection exists because that one line of text is translated: a plugin reads a player's language at
 * join through {@code :common}'s {@link PlayerLocales}. One indexed lookup per join, no writes, ever.
 */
public final class LimboPlugin extends JavaPlugin {

    private ConfigHandle<LimboSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;

    /**
     * Its own file and handle, read once at enable.
     *
     * See {@code ColoursSpec}'s own javadoc for why {@code /limbo reload} does not touch it.
     */
    private ConfigHandle<ColoursSpec> coloursHandle;

    /** The tone palette this server paints a reply with. Set once in {@link #start()}. */
    private ToneColours colours;

    private HikariDataSource pool;
    private AccessDirectory access;
    private AdminWatch adminWatch;

    /** What a non-admin may type here, and what their client is told exists. */
    private CommandFilter commandFilter;

    /** The thread a command sent to another process waits on. Shut down before the pool. */
    private java.util.concurrent.ScheduledExecutorService commandWaiter;

    private WaitingRoom room;
    private LimboChannel channel;
    private org.bukkit.scheduler.BukkitTask heartbeat;

    /**
     * <b>One try around the whole start.</b>
     *
     * Anything that throws out of {@code onEnable} leaves Paper having disabled this plugin while the server carries
     * on running without it - the exact state {@link #severe} exists to prevent.
     *
     * {@code RuntimeException} only: {@code ConfigException} is checked and {@code start()} answers it where it is
     * thrown.
     */
    @Override
    public void onEnable() {
        // Loads the class every disable step goes through, while the jar it lives in still exists; see Shutdown#warmUp.
        eu.nordtal.s2.common.health.Shutdown.warmUp();
        // {server.name} in every message: the plugin's name is the service's.
        eu.nordtal.s2.common.message.context.Contexts.server(getName());
        try {
            start();
        } catch (final Refusal refusal) {
            // Already logged, and the shutdown is already in motion - see severe(String).
        } catch (final RuntimeException failure) {
            severe("limbo is not starting: " + failure.getMessage());
        }
    }

    /** Everything a start consists of. Throws rather than half-starting; see {@link #onEnable()}. */
    private void start() {
        loadConfigHandles();
        final LimboSpec config = configHandle.get();
        colours = ToneColours.parse(Configs.declared(coloursHandle.get()), getLogger()::warning);
        final WaitingWorld world = requireWorld(config);

        pool = LimboPool.open(databaseHandle.get());
        access = AccessDirectory.using(pool);
        final PlayerLocales locales = new PlayerLocales(access::locale);
        final Messages messages = loadMessages();

        wirePresence(config, world, messages, locales);
        wireCommands(config, messages, locales);

        startHeartbeat();
        getLogger()
                .info("limbo enabled - waiting world '" + config.worldName() + "', title refreshed " + "every "
                        + config.titleRefreshSeconds() + "s, speaking " + LimboProtocol.CHANNEL);
    }

    /** Loads {@code config.yml}, {@code database.yml} and {@code colours.yml}, in that order. */
    private void loadConfigHandles() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), slf4j());
            databaseHandle = Configs.database(getDataFolder().toPath(), slf4j());
            coloursHandle = Configs.colours(getDataFolder().toPath(), slf4j());
        } catch (final ConfigException exception) {
            throw severe(
                    "limbo is not starting because its configuration could not be read: " + exception.getMessage());
        }
    }

    /** The waiting world, or a refusal: a waiting room with nowhere to wait must not start. */
    private WaitingWorld requireWorld(final LimboSpec config) {
        final WaitingWorld world = WaitingWorld.loadOrCreate(this, config);
        if (world == null) {
            throw severe("limbo could not create or load its waiting world '" + config.worldName()
                    + "'. Without it every login would be spawned into this server's own level-name "
                    + "world, which is the one thing a waiting room must not show. Stopping the "
                    + "server rather than accepting logins onto it.");
        }
        return world;
    }

    /** The two message bundles, this module's own winning where both declare a key. */
    private Messages loadMessages() {
        final Messages messages = Messages.load(
                getClass().getClassLoader(),
                java.util.List.of("messages/commands", "messages/limbo"),
                getDataFolder().toPath().resolve("messages"),
                Locale.ENGLISH,
                Locale.GERMAN);
        messages.unknownOverrideKeys()
                .forEach(key -> getLogger()
                        .warning("the message override names " + key + ", which no bundle declares - it is stored"
                                + " and never used; check the spelling"));
        return messages;
    }

    /** The waiting room itself, the listeners around it, and the admin watch they share. */
    private void wirePresence(
            final LimboSpec config, final WaitingWorld world, final Messages messages, final PlayerLocales locales) {
        final AdminOperators operators = BukkitOps.create();
        // ops.json is persistent, so an admin left in it by a crash would otherwise outlive this sweep.
        operators.sweep();
        // Shared: the gate fills the admin flag at pre-login and both the fullness answer and the grant read it back.
        final FullServerAdmission admission = new FullServerAdmission();

        room = new WaitingRoom(this, config, messages, locales, world);
        room.start();
        channel = new LimboChannel(this, room);
        channel.register();

        getServer()
                .getPluginManager()
                .registerEvents(
                        new PresenceListener(this, world, room, channel, locales, messages, operators, admission),
                        this);
        // The only login this can ever refuse is an admin's: admins are the only players a full network lets past.
        getServer().getPluginManager().registerEvents(new FullServerGate(access, admission, slf4j()), this);
        // Read once per session would leave a revoked admin with operator until they disconnect; see AdminWatch.
        adminWatch = new AdminWatch(this, access, operators, admission, admins -> {}, slf4j());
    }

    /** The command layer: the outbox to other processes, the inbox from them, and the client-side filter. */
    private void wireCommands(final LimboSpec config, final Messages messages, final PlayerLocales locales) {
        // Built here rather than inside the inbox so that /limbo reload can move it.
        final Messages shared = PaperCommandInbox.sharedBundle(this);
        final LimboEffects chatEffects = new BukkitLimboEffects(this, BukkitLimboEffects.async(this), messages, shared);
        final CommandWiring wiring = wireCommandInbox(messages, shared);

        // CommandFilter fails open until a list is published; this only tells the client what exists.
        commandFilter = new CommandFilter(
                this,
                CommandFilter.Source.of(AllowlistDirectory.using(pool)),
                adminWatch::isAdmin,
                locales,
                messages,
                slf4j(),
                () -> colours);
        getServer().getPluginManager().registerEvents(commandFilter, this);
        commandFilter.start(java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()));

        adminWatch.start(
                java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()),
                config.adminListenEnabled()
                        ? new AdminWatch.DatabaseConnection(
                                databaseHandle.get().jdbcUrl(),
                                databaseHandle.get().username(),
                                databaseHandle.get().password(),
                                databaseHandle.get().queryTimeoutSeconds())
                        : null,
                java.util.stream.Stream.concat(wiring.inbox().refreshes().stream(), commandFilter.refreshes().stream())
                        .toList(),
                java.util.stream.Stream.concat(wiring.inbox().channels().stream(), commandFilter.channels().stream())
                        .toList());

        getLifecycleManager()
                .registerEventHandler(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        event -> LimboCommand.build(
                                        this,
                                        messages,
                                        locales,
                                        // Not FullServerAdmission's set, which fills only near the player cap.
                                        adminWatch::isAdmin,
                                        access::linkedDiscordAccount,
                                        wiring.outbox(),
                                        chatEffects,
                                        pool,
                                        () -> colours)
                                .forEach(node -> event.registrar().register(node)));
    }

    /** The command inbox and its matching outbox, built together since both share one {@code requests} table. */
    private record CommandWiring(PaperCommandInbox inbox, Outbox outbox) {}

    private CommandWiring wireCommandInbox(final Messages messages, final Messages shared) {
        commandWaiter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        final eu.nordtal.s2.common.command.CommandRequests requests =
                eu.nordtal.s2.common.command.CommandRequests.borrowing(pool);
        final Outbox outbox = new Outbox(
                requests,
                commandWaiter,
                (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));

        final PaperCommandInbox inbox = new PaperCommandInbox(this, Target.LIMBO, requests, access, shared);
        // A scheduled effect would settle the request row before the command produced its answer; register refuses one.
        LimboCommands.all()
                .forEach(command ->
                        inbox.register(command, new BukkitLimboEffects(this, Runnable::run, messages, shared)));
        inbox.start(this);
        return new CommandWiring(inbox, outbox);
    }

    /**
     * The container readiness marker - see {@link Readiness}.
     *
     * It is the <b>last</b> thing {@code start()} does, so a marker on disk means this plugin got all the way through.
     * Written from Bukkit's async scheduler, which is re-queued by the main thread, so a server frozen mid-tick goes
     * stale rather than staying green on an open port.
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler().runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    @Override
    public void onDisable() {
        // The marker is deliberately not deleted: going stale is the signal, and it costs nothing here.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        if (channel != null) {
            quietly("channel.unregister", channel::unregister);
        }
        if (room != null) {
            quietly("room.stop", room::stop);
        }
        // Before the pool: the listener thread has its own connection, but a refresh in flight reads through the pool.
        if (adminWatch != null) {
            quietly("adminWatch.close", adminWatch::close);
        }
        // access.close() is a no-op, since AccessDirectory.using(...) never owns the pool it is handed.
        if (commandWaiter != null) {
            // Before the pool: a wait in flight reads the request row through it.
            quietly("commandWaiter.shutdownNow", commandWaiter::shutdownNow);
        }
        if (pool != null) {
            quietly("pool.close", pool::close);
        }
        getLogger().info("limbo disabled");
    }

    /** One disable step, isolated from the next - see {@link eu.nordtal.s2.common.health.Shutdown}. */
    private void quietly(final String what, final Runnable step) {
        eu.nordtal.s2.common.health.Shutdown.quietly(
                what, step, (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    // JavaPlugin#getLogger() returns java.util.logging.Logger; jcore's ConfigLoader wants an slf4j.Logger.
    private org.slf4j.Logger slf4j() {
        return org.slf4j.LoggerFactory.getLogger(LimboPlugin.class);
    }

    /**
     * The plugin cannot run, so neither can this server.
     *
     * It takes the server down with it because these are our own dedicated backends: disabling the plugin alone
     * leaves a container that is up and green with no season on it. The heartbeat marker reports that state, but an
     * unhealthy container is only a red square - Docker restarts nothing on health alone - so the shutdown is what
     * actually stops it.
     */
    private Refusal severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
        return new Refusal();
    }

    /**
     * Thrown by every {@link #severe(String)} call in {@link #start()}, and by nothing else.
     *
     * {@code severe} already logs the reason and starts the shutdown; this only gives
     * {@code throw severe(...)} a real {@code throw}, so NullAway's {@code KnownInitializers} check
     * on {@link #start()} sees that nothing after it runs. {@link #onEnable()} catches it separately
     * so the message is not logged twice.
     */
    private static final class Refusal extends RuntimeException {
        private Refusal() {
            super(null, null, false, false);
        }
    }
}
