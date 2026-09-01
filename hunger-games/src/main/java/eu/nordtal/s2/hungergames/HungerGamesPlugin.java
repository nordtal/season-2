package eu.nordtal.s2.hungergames;

import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
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

    /** The one game this plugin is currently tracking, refreshed from the database at enable and after decision. */
    private volatile UUID currentGameId;

    @Override
    public void onEnable() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), getLogger0());
            databaseHandle = Configs.database(getDataFolder().toPath(), getLogger0());
        } catch (final ConfigException exception) {
            getLogger().severe("hunger-games is not starting because its configuration could not be read: "
                    + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        final HungerGamesSpec config = configHandle.get();

        pool = HungerGamesPool.open(databaseHandle.get());
        final Jdbi jdbi = Jdbi.create(pool).installPlugin(new SqlObjectPlugin()).installPlugin(new PostgresPlugin());
        dao = jdbi.onDemand(HungerGamesDao.class);

        messages = Messages.load(getClass().getClassLoader(), "messages/hunger-games", Locale.ENGLISH, Locale.GERMAN);
        locales = new PlayerLocales(mcUuid -> {
            final var discordId = dao.discordIdOf(mcUuid);
            return discordId.map(id -> Locales.parse(dao.localeOf(id).orElse(null))).orElse(Locales.DEFAULT);
        });

        final World world = resolveWorld(config);
        if (world == null) {
            getLogger().severe("hunger-games could not find/load world '" + config.worldName()
                    + "' - the plugin is disabling itself rather than running without an event world");
            getServer().getPluginManager().disablePlugin(this);
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
        getServer().getPluginManager().registerEvents(new PresenceListener(this, locales, bodies, state), this);
        getServer().getPluginManager().registerEvents(
                new CombatListener(this, dao, state, bodies, border, winTracker, this::onGameDecided), this);

        registerCommands(config, world);

        getLogger().info("hunger-games enabled");
    }

    @Override
    public void onDisable() {
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
}
