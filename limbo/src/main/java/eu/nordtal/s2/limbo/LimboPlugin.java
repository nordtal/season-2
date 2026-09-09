package eu.nordtal.s2.limbo;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.limbo.command.BukkitLimboEffects;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.command.CommandFilter;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.command.AllowlistDirectory;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.limbo.LimboProtocol;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.limbo.command.LimboCommand;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.limbo.config.Configs;
import eu.nordtal.s2.limbo.config.DatabaseSpec;
import eu.nordtal.s2.limbo.config.LimboSpec;
import eu.nordtal.s2.limbo.db.LimboPool;
import eu.nordtal.s2.limbo.listener.FullServerGate;
import eu.nordtal.s2.limbo.listener.PresenceListener;
import eu.nordtal.s2.limbo.net.LimboChannel;
import eu.nordtal.s2.limbo.waiting.WaitingRoom;
import eu.nordtal.s2.limbo.world.WaitingWorld;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * The season 2 waiting room. Every login lands here first, whatever the phase, and leaves when the
 * proxy says so.
 *
 * <p>What it shows is <b>nothing</b>: black, no visible world, no other players and no chat. A
 * title in the player's language says what they are waiting for, and that is the entire interface.
 *
 * <p>The three things that end a wait - the pack is applied, the phase's backend is up, maintenance
 * is over - are all the proxy's to know and all arrive as a {@code nordtal:limbo} plugin message
 * ({@link LimboProtocol}). This plugin's only outgoing message says "this player has arrived"; it
 * never says where anybody should go, because routing lives in one process and this is not it.</p>
 *
 * <p>The database connection exists because that one line of text is translated: a plugin reads a
 * player's language at join through {@code :common}'s {@link PlayerLocales}. One indexed lookup per
 * join, no writes, ever.</p>
 */
public final class LimboPlugin extends JavaPlugin {

    private ConfigHandle<LimboSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
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
     * <b>One try around the whole start.</b> Anything that throws out of {@code onEnable} leaves
     * Paper having disabled this plugin while the server carries on running without it - the exact
     * state {@link #severe} exists to prevent.
     *
     * <p>{@code RuntimeException} only: {@code ConfigException} is checked and {@code start()}
     * answers it where it is thrown.</p>
     */
    @Override
    public void onEnable() {
        // Before anything else, and it has to be here: this loads the class every disable step
        // below goes through, while the jar it lives in still exists. See Shutdown#warmUp.
        eu.nordtal.s2.common.health.Shutdown.warmUp();
        try {
            start();
        } catch (final RuntimeException failure) {
            severe("limbo is not starting: " + failure.getMessage());
        }
    }

    /** Everything a start consists of. Throws rather than half-starting; see {@link #onEnable()}. */
    private void start() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), slf4j());
            databaseHandle = Configs.database(getDataFolder().toPath(), slf4j());
        } catch (final ConfigException exception) {
            severe("limbo is not starting because its configuration could not be read: "
                    + exception.getMessage());
            return;
        }

        final LimboSpec config = configHandle.get();

        final WaitingWorld world = WaitingWorld.loadOrCreate(this, config);
        if (world == null) {
            severe("limbo could not create or load its waiting world '" + config.worldName()
                    + "'. Without it every login would be spawned into this server's own level-name "
                    + "world, which is the one thing a waiting room must not show. Stopping the "
                    + "server rather than accepting logins onto it.");
            return;
        }

        pool = LimboPool.open(databaseHandle.get());
        access = AccessDirectory.using(pool);
        final PlayerLocales locales = new PlayerLocales(access::locale);

        // Two roots, shared first: this module's own bundle wins where both declare a key, which is
        // how limbo puts its colours back on a line the shared bundle has to leave plain.
        final Messages messages = Messages.load(getClass().getClassLoader(),
                java.util.List.of("messages/commands", "messages/limbo"),
                getDataFolder().toPath().resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        messages.unknownOverrideKeys().forEach(key -> getLogger().warning(
                "the message override names " + key + ", which no bundle declares - it is stored"
                        + " and never used; check the spelling"));

        // Admins are operators for as long as they are admins. The sweep runs before a single join
        // can be handled: ops.json is persistent, so anybody left in it by a crash would otherwise
        // still be an operator on this start.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();

        // One instance, shared: the gate fills the admin flag at pre-login and both the fullness
        // answer and the operator grant read it back. Two instances would be two caches, one of
        // them always empty.
        final FullServerAdmission admission = new FullServerAdmission();

        room = new WaitingRoom(this, config, messages, locales, world);
        room.start();

        channel = new LimboChannel(this, room);
        channel.register();

        getServer().getPluginManager()
                .registerEvents(new PresenceListener(this, world, room, channel, locales, messages,
                        operators, admission), this);
        // The player cap on this server is the network's own now, so Paper can refuse a login for
        // fullness - and the only login it would ever refuse is an admin's, because admins are the
        // only players the proxy lets past a full network. See FullServerAdmission.
        getServer().getPluginManager()
                .registerEvents(new FullServerGate(access, admission, slf4j()), this);

        // ...and keeps being one only for as long as the database says so. Without this the flag is
        // read once per session and a revoked admin keeps operator until they disconnect; see
        // AdminWatch. limbo passes no extra cache because it holds none - it renders one title.
        adminWatch = new AdminWatch(this, access, operators, admission, admins -> { }, slf4j());

        // Built before the admin watch is started, because the inbox rides on that watch's LISTEN
        // connection: one connection carrying nordtal_admin and nordtal_command.
        //
        // The shared bundle is built here and not inside the inbox so that /limbo reload can move
        // it; one that never reloaded would answer a Discord admin with the wording this process
        // started with.
        final eu.nordtal.s2.common.message.Messages shared = PaperCommandInbox.sharedBundle(this);
        final LimboEffects chatEffects =
                new BukkitLimboEffects(this, BukkitLimboEffects.async(this), messages, shared);
        commandWaiter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        final eu.nordtal.s2.common.command.CommandRequests requests =
                eu.nordtal.s2.common.command.CommandRequests.borrowing(pool);
        final Outbox outbox = new Outbox(requests, commandWaiter,
                (message, failure) -> getLogger()
                        .log(java.util.logging.Level.WARNING, message, failure));

        final PaperCommandInbox inbox =
                new PaperCommandInbox(this, Target.LIMBO, requests, access, shared);
        // Inline: the inbox settles a request row when the command returns, so scheduled effects
        // would write the answer before the command produced it. CommandInbox#register refuses them.
        LimboCommands.all().forEach(command ->
                inbox.register(command, new BukkitLimboEffects(this, Runnable::run, messages,
                        shared)));
        inbox.start(this);

        // The proxy refuses a command before it reaches this server, which is the enforcement; this
        // is the half the proxy cannot do - what this server tells a client exists at all.
        // CommandFilter fails OPEN, and says so, when no list has been published yet.
        commandFilter = new CommandFilter(this,
                CommandFilter.Source.of(AllowlistDirectory.using(pool)),
                adminWatch::isAdmin, locales, messages, slf4j());
        getServer().getPluginManager().registerEvents(commandFilter, this);
        commandFilter.start(java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()));

        adminWatch.start(java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()),
                config.adminListenEnabled()
                        ? new AdminWatch.DatabaseConnection(databaseHandle.get().jdbcUrl(),
                                databaseHandle.get().username(), databaseHandle.get().password(),
                                databaseHandle.get().queryTimeoutSeconds())
                        : null,
                java.util.stream.Stream.concat(inbox.refreshes().stream(),
                        commandFilter.refreshes().stream()).toList(),
                java.util.stream.Stream.concat(inbox.channels().stream(),
                        commandFilter.channels().stream()).toList());

        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                event -> LimboCommand.build(this, messages, locales,
                                // The admin watch's own set, not FullServerAdmission's: that one
                                // is filled at pre-login only when the server is near its cap, and
                                // limbo never is - it would answer "nobody is an admin", for ever.
                                adminWatch::isAdmin, access::linkedDiscordAccount,
                                outbox, chatEffects, pool)
                        .forEach(node -> event.registrar().register(node)));

        startHeartbeat();

        getLogger().info("limbo enabled - waiting world '" + config.worldName() + "', title refreshed "
                + "every " + config.titleRefreshSeconds() + "s, speaking " + LimboProtocol.CHANNEL);
    }

    /**
     * The container readiness marker - see {@link Readiness}.
     *
     * <p>It is the <b>last</b> thing {@code start()} does, so a marker on disk means this plugin got
     * all the way through. Written from Bukkit's async scheduler, which is re-queued by the main
     * thread, so a server frozen mid-tick goes stale rather than staying green on an open port.</p>
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    @Override
    public void onDisable() {
        // Stops the beat, so a server that is going down stops claiming to be up. The marker is
        // deliberately not deleted: going stale is the signal, and it costs nothing here.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        if (channel != null) {
            quietly("channel.unregister", channel::unregister);
        }
        if (room != null) {
            quietly("room.stop", room::stop);
        }
        // Before the pool: the listener thread is parked on a connection of its own, but a refresh
        // already in flight reads through the pool.
        if (adminWatch != null) {
            quietly("adminWatch.close", adminWatch::close);
        }
        // access.close() is a no-op - AccessDirectory.using(...) never owns the pool it is handed -
        // so this plugin closes the pool it built itself.
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
        eu.nordtal.s2.common.health.Shutdown.quietly(what, step,
                (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    // JavaPlugin#getLogger() returns java.util.logging.Logger; jcore's ConfigLoader wants an
    // slf4j.Logger, matching every other module's Configs class - this is the one adapter point.
    private org.slf4j.Logger slf4j() {
        return org.slf4j.LoggerFactory.getLogger(LimboPlugin.class);
    }

    /**
     * The plugin cannot run, so neither can this server.
     *
     * <p>It takes the server down with it because these are our own dedicated backends: disabling
     * the plugin alone leaves a container that is up and green with no season on it. The heartbeat
     * marker reports that state, but an unhealthy container is only a red square - Docker restarts
     * nothing on health alone - so the shutdown is what actually stops it.</p>
     */
    private void severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }



}
