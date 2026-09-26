package eu.nordtal.s2.hungergames;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.command.BukkitHungerGamesEffects;
import eu.nordtal.s2.hungergames.command.HungerGamesCommand;
import eu.nordtal.s2.hungergames.config.ColoursSpec;
import eu.nordtal.s2.hungergames.config.Configs;
import eu.nordtal.s2.hungergames.config.DatabaseSpec;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.config.SoundsSpec;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.HungerGamesPool;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.hungergames.game.Ceremony;
import eu.nordtal.s2.hungergames.game.HungerGamesManager;
import eu.nordtal.s2.hungergames.game.WinTracker;
import eu.nordtal.s2.hungergames.hud.HudRenderer;
import eu.nordtal.s2.hungergames.listener.CombatListener;
import eu.nordtal.s2.hungergames.listener.FreezeListener;
import eu.nordtal.s2.hungergames.listener.FullServerGate;
import eu.nordtal.s2.hungergames.listener.PresenceListener;
import eu.nordtal.s2.hungergames.lobby.Lobby;
import eu.nordtal.s2.hungergames.lobby.LobbyMaps;
import eu.nordtal.s2.hungergames.loot.LootRefill;
import eu.nordtal.s2.hungergames.player.ArenaComposition;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jspecify.annotations.Nullable;

/** The hunger games start event of season 2. */
public final class HungerGamesPlugin extends JavaPlugin {

    private ConfigHandle<HungerGamesSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;

    /**
     * Its own file and handle so {@code /hg reload} can re-read it mid-game.
     *
     * {@link #configHandle} deliberately cannot be reloaded: the border schedule, loot timings and
     * spawn towers are bound once and a game is a running clock.
     */
    private ConfigHandle<SoundsSpec> soundsHandle;

    /**
     * Its own file and handle, read once at enable.
     *
     * {@code /hg reload} does not touch this one - see {@code ColoursSpec}'s own javadoc for why -
     * so there is nothing to reload it against.
     */
    private ConfigHandle<ColoursSpec> coloursHandle;

    /** The tone palette this server paints a reply with. Set once in {@link #start()}. */
    private ToneColours colours;

    private HikariDataSource pool;
    private AdminWatch adminWatch;

    /** What a non-admin may type here, and what their client is told exists. */
    private eu.nordtal.s2.papercommon.command.CommandFilter commandFilter;

    /**
     * The command layer.
     *
     * Two effects instances, and the difference is the executor: the chat one schedules, the
     * inbox's runs inline because the inbox settles a request row when the command returns.
     * {@code CommandInbox#register} refuses the wrong one at startup.
     */
    private HungerGamesEffects chatEffects;

    private Outbox outbox;
    private ScheduledExecutorService commandWaiter;
    private HungerGamesDao dao;

    /** Held so {@code /hg reload} can swap what it answers; every listener has this one instance. */
    private HungerGamesSounds sounds;

    private Messages messages;
    /** {@code :commands}' bundle as the inbox renders it - a second view of the same files. */
    private Messages sharedMessages;

    private PlayerLocales locales;

    private final GameState state = new GameState();
    private final PlayerBodies bodies = new PlayerBodies();

    private BorderController border;
    private LootRefill loot;
    private HudRenderer hud;
    private Lobby lobby;
    private WinTracker winTracker;
    private Ceremony ceremony;
    private HungerGamesManager manager;
    private org.bukkit.scheduler.BukkitTask heartbeat;

    /** The one game this plugin is currently tracking, refreshed from the database at enable and after decision. */
    private volatile @Nullable UUID currentGameId;

    /**
     * One try around the whole start.
     *
     * Anything that throws after the config read would otherwise escape {@code onEnable}, leaving
     * Paper running this server without the plugin - the exact state {@link #severe} exists to
     * prevent.
     */
    @Override
    public void onEnable() {
        // Must be first: loads the class every disable step below goes through, while the jar still exists.
        eu.nordtal.s2.common.health.Shutdown.warmUp();
        // {server.name} in every message: the plugin's name is the service's.
        eu.nordtal.s2.common.message.context.Contexts.server(getName());
        try {
            start();
        } catch (final Refusal refusal) {
            // Already logged, and the shutdown is already in motion - see severe(String).
        } catch (final RuntimeException failure) {
            severe("hunger-games is not starting: " + failure.getMessage());
        }
    }

    /** Everything a start consists of. Throws rather than half-starting; see {@link #onEnable()}. */
    private void start() {
        final HungerGamesSpec config = loadConfigAndMessages();
        final World world = resolveGameWorld(config);
        wireGameSystems(config, world);
        final AdminHooks hooks = wireListeners();
        final PaperCommandInbox inbox = wireAdminWatchAndCommands(config, world, hooks);
        wireCommandFilterAndAdminWatch(config, inbox);
        startHeartbeat();
        getLogger().info("hunger-games enabled");
    }

    /** Loads every config file, the database pool and DAO, and the message/locale machinery. */
    private HungerGamesSpec loadConfigAndMessages() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), getLogger0());
            databaseHandle = Configs.database(getDataFolder().toPath(), getLogger0());
            soundsHandle = Configs.sounds(getDataFolder().toPath(), getLogger0());
            coloursHandle = Configs.colours(getDataFolder().toPath(), getLogger0());
        } catch (final ConfigException exception) {
            throw severe("hunger-games is not starting because its configuration could not be read: "
                    + exception.getMessage());
        }

        final HungerGamesSpec config = configHandle.get();
        // Built before anything that plays one: a bad key here is reported and the category silenced.
        sounds = HungerGamesSounds.of(soundsHandle.get(), getLogger()::warning);
        colours = ToneColours.parse(Configs.declared(coloursHandle.get()), getLogger()::warning);

        pool = HungerGamesPool.open(databaseHandle.get());
        final Jdbi jdbi = Jdbi.create(pool).installPlugin(new SqlObjectPlugin()).installPlugin(new PostgresPlugin());
        dao = jdbi.onDemand(HungerGamesDao.class);

        // Three roots, most general first; later roots win, so this module's keys beat both others.
        messages = Messages.load(
                getClass().getClassLoader(),
                java.util.List.of("messages/paper-common", "messages/commands", "messages/hunger-games"),
                getDataFolder().toPath().resolve("messages"),
                Locale.ENGLISH,
                Locale.GERMAN);
        messages.unknownOverrideKeys()
                .forEach(key -> getLogger()
                        .warning("the message override names " + key + ", which no bundle declares - it is stored"
                                + " and never used; check the spelling"));
        locales = new PlayerLocales(mcUuid -> {
            final var discordId = dao.discordIdOf(mcUuid);
            return discordId
                    .map(id -> Locales.parse(dao.localeOf(id).orElse(null)))
                    .orElse(Locales.DEFAULT);
        });
        return config;
    }

    /** The event world, or a refusal that stops the server rather than running with none loaded. */
    private World resolveGameWorld(final HungerGamesSpec config) {
        final World world = resolveWorld(config);
        if (world == null) {
            throw severe("hunger-games could not find/load world '" + config.worldName()
                    + "' - stopping the server rather than running an event server with no event "
                    + "world on it");
        }
        return world;
    }

    /** Builds the border, loot, HUD, lobby, ceremony and manager, and starts the lobby broadcast. */
    private void wireGameSystems(final HungerGamesSpec config, final World world) {
        border = new BorderController(this, world, config, messages, locales, sounds);
        loot = new LootRefill(this, world, config, border, messages, locales, sounds);
        // winTracker before hud: the HUD reads the living count off it on every redraw.
        winTracker = new WinTracker(dao, messages, locales, sounds);
        hud = new HudRenderer(this, world, config, messages, locales, border, state, winTracker, loot);
        lobby = new Lobby(this, dao, config, messages, locales);
        ceremony = new Ceremony(messages, locales, sounds);
        manager = new HungerGamesManager(this, dao, config, messages, locales, bodies, state, border, sounds);

        refreshCurrentGame();

        // Lobby map slicing - tolerant of missing artwork.
        new LobbyMaps(this, config).render(world);

        lobby.startBroadcasting(world, () -> currentGameId);
    }

    /** The two caches {@link #wireAdminWatchAndCommands} needs but does not itself own. */
    private record AdminHooks(AdminOperators operators, FullServerAdmission admission) {}

    /** Registers the freeze, full-server-gate, presence and combat listeners. */
    private AdminHooks wireListeners() {
        getServer().getPluginManager().registerEvents(new FreezeListener(manager), this);
        // Admins are operators for as long as they are admins; the sweep runs before any join is handled.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();

        // One instance, shared by the gate, the fullness answer and the operator grant - two would leave one empty.
        final FullServerAdmission admission = new FullServerAdmission();

        getServer().getPluginManager().registerEvents(new FullServerGate(dao, admission, getLogger0()), this);
        // The five system lines; the death line keeps vanilla's own component for killer and weapon.
        final ArenaComposition composition = new ArenaComposition(locales);
        final SystemLines systemLines = new SystemLines(composition::of, messages, locales);
        getServer().getPluginManager().registerEvents(systemLines, this);
        getServer()
                .getPluginManager()
                .registerEvents(
                        new PresenceListener(this, locales, bodies, state, messages, operators, admission, systemLines),
                        this);
        getServer()
                .getPluginManager()
                .registerEvents(
                        new CombatListener(
                                this,
                                dao,
                                state,
                                bodies,
                                border,
                                winTracker,
                                sounds,
                                systemLines,
                                composition,
                                this::onGameDecided),
                        this);
        return new AdminHooks(operators, admission);
    }

    /** Wires the admin watch, the two command-effects instances, the outbox and the command inbox. */
    private PaperCommandInbox wireAdminWatchAndCommands(
            final HungerGamesSpec config, final World world, final AdminHooks hooks) {
        // Without this the admin flag is read once per session and a revoked admin keeps operator until they leave.
        adminWatch = new AdminWatch(
                this,
                eu.nordtal.s2.common.access.AccessDirectory.using(pool),
                hooks.operators(),
                hooks.admission(),
                admins -> {},
                getLogger0());
        final eu.nordtal.s2.common.access.AccessDirectory access =
                eu.nordtal.s2.common.access.AccessDirectory.using(pool);
        final java.util.function.BooleanSupplier reloadSounds = this::reloadSounds;
        chatEffects = new BukkitHungerGamesEffects(
                this,
                BukkitHungerGamesEffects.async(this),
                dao,
                config,
                lobby,
                this::currentGameIdNow,
                gameId -> startGame(gameId, world),
                reloadSounds,
                this::reloadMessages);

        commandWaiter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        final eu.nordtal.s2.common.command.CommandRequests requests =
                eu.nordtal.s2.common.command.CommandRequests.borrowing(pool);
        outbox = new Outbox(
                requests,
                commandWaiter,
                (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));

        // Built here rather than inside the inbox so /hg reload can swap it too.
        sharedMessages = PaperCommandInbox.sharedBundle(this);
        final PaperCommandInbox inbox =
                new PaperCommandInbox(this, Target.HUNGER_GAMES, requests, access, sharedMessages);
        // Inline, on purpose - see the field comment.
        final HungerGamesEffects inboxEffects = new BukkitHungerGamesEffects(
                this,
                Runnable::run,
                dao,
                config,
                lobby,
                this::currentGameIdNow,
                gameId -> startGame(gameId, world),
                reloadSounds,
                this::reloadMessages);
        HungerGamesCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.start(this);

        registerCommands();
        return inbox;
    }

    /** Builds the command allowlist filter and starts both it and the admin watch polling/listening. */
    private void wireCommandFilterAndAdminWatch(final HungerGamesSpec config, final PaperCommandInbox inbox) {
        // The proxy enforces the allowlist; this only tells a client what exists. Fails open with no list yet.
        commandFilter = new eu.nordtal.s2.papercommon.command.CommandFilter(
                this,
                eu.nordtal.s2.papercommon.command.CommandFilter.Source.of(
                        eu.nordtal.s2.common.command.AllowlistDirectory.using(pool)),
                adminWatch::isAdmin,
                locales,
                messages,
                getLogger0(),
                () -> colours,
                sounds::play);
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
                java.util.stream.Stream.concat(inbox.refreshes().stream(), commandFilter.refreshes().stream())
                        .toList(),
                java.util.stream.Stream.concat(inbox.channels().stream(), commandFilter.channels().stream())
                        .toList());
    }

    /**
     * The container readiness marker ({@link Readiness}).
     *
     * Called last in {@code start()} so a marker on disk means the plugin got all the way through;
     * every refusal above returns first. The async task is re-queued by the main-thread heartbeat,
     * so a server frozen mid-tick stops beating rather than staying green on an open port.
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler().runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    @Override
    public void onDisable() {
        // Stops the beat; the marker is deliberately not deleted, since going stale is the signal.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        if (lobby != null) {
            quietly("lobby.stop", lobby::stop);
        }
        if (hud != null) {
            quietly("hud.stop", hud::stop);
        }
        if (loot != null) {
            quietly("loot.cancelAll", loot::cancelAll);
        }
        if (border != null) {
            quietly("border.stop", border::stop);
        }
        // Before the pool: the listener is on its own connection, but a refresh in flight reads through the pool.
        if (adminWatch != null) {
            quietly("adminWatch.close", adminWatch::close);
        }
        if (commandWaiter != null) {
            // Before the pool: a wait in flight reads the request row through it.
            quietly("commandWaiter.shutdownNow", commandWaiter::shutdownNow);
        }
        if (pool != null) {
            quietly("pool.close", pool::close);
        }
        getLogger().info("hunger-games disabled");
    }

    /** One disable step, isolated from the next - see {@link eu.nordtal.s2.common.health.Shutdown}. */
    private void quietly(final String what, final Runnable step) {
        eu.nordtal.s2.common.health.Shutdown.quietly(
                what, step, (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    private void registerCommands() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final HungerGamesCommand command = new HungerGamesCommand(
                    this, dao, messages, locales, lobby, sounds, () -> currentGameId, () -> colours);
            command.build(outbox, chatEffects, adminWatch::isAdmin, pool)
                    .forEach(node -> event.registrar().register(node));
        });
    }

    /**
     * Runs off the main thread.
     *
     * The roster is read here rather than in the {@code onReleased} callback, which runs on the
     * main thread in the tick every participant is released - and reading it up front means the
     * tracker knows who is alive before the first death is recorded.
     */
    private void startGame(final UUID gameId, final World world) {
        final List<HgMember> activeMembers = dao.activeMembersOf(gameId);
        manager.start(gameId, world, () -> {
            final Instant releasedAt = Instant.now();
            loot.scheduleAll(releasedAt);
            hud.start();
            winTracker.reset(activeMembers);
        });
    }

    /**
     * Re-reads the message bundles and the operator's override on top of them.
     *
     * Throws rather than answering a boolean so the failure reaches the console with its own message.
     */
    private void reloadMessages() {
        messages.reload();
        // The inbox's own view of the same files; its unknown keys go unreported since it holds one root.
        if (sharedMessages != null) {
            sharedMessages.reload();
        }
        messages.unknownOverrideKeys()
                .forEach(key -> getLogger()
                        .warning("the message override names " + key + ", which no bundle declares - it is"
                                + " stored and never used; check the spelling"));
    }

    private boolean reloadSounds() {
        try {
            soundsHandle.reload();
            sounds.reload(soundsHandle.get());
            getLogger().info("the sounds were reloaded");
            return true;
        } catch (final ConfigException | RuntimeException exception) {
            getLogger()
                    .severe("the sounds could not be reloaded, the running ones are unchanged: "
                            + exception.getMessage());
            return false;
        }
    }

    private void onGameDecided(final Ceremony.Decision decision) {
        final HungerGamesSpec config = configHandle.get();
        final World world = resolveWorld(config);
        if (world == null) {
            return;
        }

        hud.stop();
        loot.cancelAll();
        border.stop();

        final Location lobbyLocation = new Location(
                world, config.lobby().x(), config.lobby().y(), config.lobby().z());
        // No query: the caller read everything off the main thread, and gameId is still set before state.clear().
        ceremony.run(world, lobbyLocation, Objects.requireNonNull(state.gameId()), decision);
        state.clear();
        decidedGameId = currentGameId;
        currentGameId = null;
    }

    /**
     * The last game this server decided, so a command lookup cannot put it back.
     *
     * A query that started before the decision landed still answers with that game, and writing it
     * back into the cache the lobby broadcasts from would restart the countdown for a finished game.
     */
    private volatile @Nullable UUID decidedGameId;

    private void refreshCurrentGame() {
        currentGameId = dao.currentGame().map(game -> game.id()).orElse(null);
    }

    /**
     * The game as the database has it <em>now</em>, for the commands.
     *
     * The cache above is filled once at enable, so a game registered in Discord after this server
     * started would be invisible to {@code /hg} until a restart. Runs on the effects' executor,
     * never the main thread, which is why the lobby's own broadcast still reads the cache.
     */
    private @Nullable UUID currentGameIdNow() {
        final UUID found = dao.currentGame()
                .map(game -> game.id())
                .filter(id -> !id.equals(decidedGameId))
                .orElse(null);
        // Answer the local value: onGameDecided may clear the field between the query and the return.
        if (found != null) {
            currentGameId = found;
        }
        return found;
    }

    private @Nullable World resolveWorld(final HungerGamesSpec config) {
        final World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            getLogger()
                    .warning("World '" + config.worldName() + "' is not currently loaded - "
                            + "hunger-games cannot run without its event world");
        }
        return world;
    }

    // getLogger() answers java.util.logging.Logger; jcore's ConfigLoader wants slf4j's - the one adapter point.
    private org.slf4j.Logger getLogger0() {
        return org.slf4j.LoggerFactory.getLogger(HungerGamesPlugin.class);
    }

    /**
     * The plugin cannot run, so neither can this server.
     *
     * On a dedicated backend, disabling the plugin and letting Paper carry on leaves a container
     * that is up, green and has no season on it - nothing outside the JVM restarts on health alone,
     * so the shutdown has to happen here.
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
     * {@code severe} already logs the reason and starts the shutdown; this only gives {@code throw severe(...)} a
     * real {@code throw}, so NullAway's {@code KnownInitializers} check on {@link #start()} sees that nothing after
     * it runs. {@link #onEnable()} catches it separately so the message is not logged twice.
     */
    private static final class Refusal extends RuntimeException {
        private Refusal() {
            super(null, null, false, false);
        }
    }
}
