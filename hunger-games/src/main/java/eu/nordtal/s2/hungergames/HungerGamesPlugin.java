package eu.nordtal.s2.hungergames;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.command.HungerGamesCommand;
import eu.nordtal.s2.hungergames.config.Configs;
import eu.nordtal.s2.hungergames.config.DatabaseSpec;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.db.HungerGamesPool;
import eu.nordtal.s2.hungergames.game.Ceremony;
import eu.nordtal.s2.hungergames.game.GameState;
import eu.nordtal.s2.hungergames.game.HungerGamesManager;
import eu.nordtal.s2.hungergames.game.WinTracker;
import eu.nordtal.s2.hungergames.hud.HudRenderer;
import eu.nordtal.s2.hungergames.listener.CombatListener;
import eu.nordtal.s2.hungergames.listener.FreezeListener;
import eu.nordtal.s2.hungergames.listener.PresenceListener;
import eu.nordtal.s2.hungergames.lobby.Lobby;
import eu.nordtal.s2.hungergames.lobby.LobbyMaps;
import eu.nordtal.s2.hungergames.loot.LootRefill;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The hunger games start event of season 2 - see docs/hunger-games.md for the concept this
 * implements in full, and this module's own package structure for where each piece lives:
 * {@code config} (the {@code @ConfigSpec}), {@code db} (the JDBI DAO over {@code hg_*} plus
 * {@code discord_user}/{@code account_link}), {@code game} (the start sequence, colours, win
 * tracking, ceremony), {@code border}, {@code loot}, {@code hud}, {@code lobby}, {@code body} (the
 * disconnected-body mechanism) and {@code command} ({@code /hg}, Brigadier only).
 */
public final class HungerGamesPlugin extends JavaPlugin {

    private ConfigHandle<HungerGamesSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
    private HikariDataSource pool;
    private HungerGamesDao dao;
    private Messages messages;
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
    private volatile UUID currentGameId;

    @Override
    public void onEnable() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), getLogger0());
            databaseHandle = Configs.database(getDataFolder().toPath(), getLogger0());
        } catch (final ConfigException exception) {
            severe("hunger-games is not starting because its configuration could not be read: "
                    + exception.getMessage());
            return;
        }

        final HungerGamesSpec config = configHandle.get();

        pool = HungerGamesPool.open(databaseHandle.get());
        final Jdbi jdbi = Jdbi.create(pool).installPlugin(new SqlObjectPlugin()).installPlugin(new PostgresPlugin());
        dao = jdbi.onDemand(HungerGamesDao.class);

        messages = Messages.load(getClass().getClassLoader(), "messages/hunger-games",
                getDataFolder().toPath().resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        messages.unknownOverrideKeys().forEach(key -> getLogger().warning(
                "the message override names " + key + ", which no bundle declares - it is stored"
                        + " and never used; check the spelling"));
        locales = new PlayerLocales(mcUuid -> {
            final var discordId = dao.discordIdOf(mcUuid);
            return discordId.map(id -> Locales.parse(dao.localeOf(id).orElse(null))).orElse(Locales.DEFAULT);
        });

        final World world = resolveWorld(config);
        if (world == null) {
            severe("hunger-games could not find/load world '" + config.worldName()
                    + "' - stopping the server rather than running an event server with no event "
                    + "world on it");
            return;
        }

        border = new BorderController(this, world, config, messages, locales);
        loot = new LootRefill(this, world, config, border, messages, locales);
        hud = new HudRenderer(this, world, config, messages, locales, border, state);
        lobby = new Lobby(this, dao, config, messages, locales);
        winTracker = new WinTracker(dao, messages, locales);
        ceremony = new Ceremony(dao, messages, locales);
        manager = new HungerGamesManager(this, dao, config, messages, locales, bodies, state, border);

        refreshCurrentGame();

        // Lobby map slicing - tolerant of missing artwork, see LobbyMaps' own documentation.
        new LobbyMaps(this, config).render(world);

        lobby.startBroadcasting(world, () -> currentGameId);

        getServer().getPluginManager().registerEvents(new FreezeListener(manager), this);
        getServer().getPluginManager().registerEvents(new PresenceListener(this, locales, bodies, state, messages), this);
        getServer().getPluginManager().registerEvents(
                new CombatListener(this, dao, state, bodies, border, winTracker, this::onGameDecided), this);

        registerCommands(config, world);

        startHeartbeat();

        getLogger().info("hunger-games enabled");
    }

    /**
     * The container readiness marker - see {@link Readiness}, and note where this call sits.
     *
     * <p>It is the <b>last</b> thing {@code onEnable} does, because that is the entire rule: every
     * refusal above returns before reaching it, so a marker on disk means this plugin got all the
     * way through. Written from Bukkit's async scheduler, which is also deliberate - a repeating
     * async task is re-queued by the main-thread heartbeat, so a server frozen mid-tick stops
     * beating and the container goes stale rather than staying green on an open port.</p>
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
            heartbeat.cancel();
        }
        if (lobby != null) {
            lobby.stop();
        }
        if (hud != null) {
            hud.stop();
        }
        if (loot != null) {
            loot.cancelAll();
        }
        if (border != null) {
            border.stop();
        }
        if (pool != null) {
            pool.close();
        }
        getLogger().info("hunger-games disabled");
    }

    private void registerCommands(final HungerGamesSpec config, final World world) {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final HungerGamesCommand command = new HungerGamesCommand(this, dao, config, messages, locales,
                    manager, lobby, () -> currentGameId, (gameId, admin) -> startGame(gameId, world));
            event.registrar().register(command.build());
        });
    }

    /**
     * Called from the async task {@code /hg start} dispatched, never from the server thread - see
     * {@code HungerGamesCommand#runAsAdmin}. The roster read therefore belongs <b>here</b>, not
     * inside the {@code onReleased} callback: that callback runs on the main thread, in the same
     * tick that every participant is released, which is the worst possible moment for a blocking
     * query. Reading it up front also removes a race the callback had - the tracker now knows who
     * is alive strictly before the first death can be recorded.
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

    private void onGameDecided(final WinTracker.Outcome outcome) {
        final HungerGamesSpec config = configHandle.get();
        final World world = resolveWorld(config);
        if (world == null) {
            return;
        }

        hud.stop();
        loot.cancelAll();
        border.stop();

        final Location lobbyLocation = new Location(world, config.lobby().x(), config.lobby().y(),
                config.lobby().z());
        ceremony.run(world, lobbyLocation, state.gameId(), outcome, dao.activeMembersOf(state.gameId()));
        state.clear();
        currentGameId = null;
    }

    private void refreshCurrentGame() {
        currentGameId = dao.currentGame().map(game -> game.id()).orElse(null);
    }

    private World resolveWorld(final HungerGamesSpec config) {
        World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            getLogger().warning("World '" + config.worldName() + "' is not currently loaded - "
                    + "hunger-games cannot run without its event world");
        }
        return world;
    }

    // JavaPlugin#getLogger() returns java.util.logging.Logger; jcore's ConfigLoader wants an
    // slf4j.Logger, matching every other module's Configs class - this is the one adapter point.
    private org.slf4j.Logger getLogger0() {
        return org.slf4j.LoggerFactory.getLogger(HungerGamesPlugin.class);
    }

    /**
     * The plugin cannot run, so neither can this server.
     *
     * <h2>Why it takes the server with it, since 2026-09-02</h2>
     * Logging and disabling alone is the convention this repository states for
     * {@code papermc-display-tags} - a plugin on somebody else's server, where "the plugin goes
     * down, the server keeps running" is plainly right. On our own dedicated backends it is plainly
     * wrong, and the first deployment showed what it costs: {@code smp}'s config threw on every
     * start, the plugin disabled itself, Paper carried on, and the container stayed up and green
     * with no season on it.
     *
     * <p>No check outside the JVM could tell that state from a healthy one when this rule was
     * written - every jar is in the folder, so the entrypoint's guard passes, and the port was open,
     * so the port check passed. Here is the only place the difference is knowable.</p>
     *
     * <p>Since 2026-09-04 the container does report it, because {@link #startHeartbeat()} is below
     * every refusal and its marker is never written on this path. That does not soften the rule: an
     * unhealthy container is a red square in Arcane and nothing else - Docker restarts nothing on
     * health alone - so without the shutdown the server would still be up, still accepting players,
     * and merely honest about it.</p>
     */
    private void severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }

}
