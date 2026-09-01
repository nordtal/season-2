package eu.nordtal.s2.smp;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.Configs;
import eu.nordtal.s2.smp.config.DatabaseSpec;
import eu.nordtal.s2.smp.config.Milestones;
import eu.nordtal.s2.smp.config.MilestonesSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.JoinGate;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.db.SmpPool;
import eu.nordtal.s2.smp.farm.FarmWorldReset;
import eu.nordtal.s2.smp.farm.FarmWorldSwap;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneState;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.pregen.PreGenerator;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.NavigateCommand;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.navigate.NavigateListener;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerComposition;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.protect.ProtectionListener;
import eu.nordtal.s2.smp.region.Box;
import eu.nordtal.s2.smp.region.Boxes;
import eu.nordtal.s2.smp.region.ConfigBoxes;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.travel.BalloonListener;
import eu.nordtal.s2.smp.travel.PortalGate;
import eu.nordtal.s2.smp.world.Datapacks;
import eu.nordtal.s2.smp.world.Worlds;

import org.bukkit.Bukkit;
import org.bukkit.World;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * The season 2 SMP: Nordtal, the farm world, the Nether and the End, plus milestones, aura,
 * prestige, duels, POIs and graves.
 *
 * <p>The concept is docs/smp.md; see the module section in CLAUDE.md for the build rules. This
 * class is wiring and startup refusals, and the refusals are the interesting part: three conditions
 * stop the plugin rather than letting it run degraded, because each of them produces damage that
 * cannot be undone afterwards.
 */
public final class SmpPlugin extends JavaPlugin {

    private ConfigHandle<SmpSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
    private ConfigHandle<MilestonesSpec> milestonesHandle;

    private HikariDataSource pool;
    private SmpDao dao;
    private Messages messages;
    private PlayerLocales locales;

    private MilestoneTrack track;
    private Worlds worlds;
    private final SeasonState season = new SeasonState();
    private Identities identities;
    private FarmWorldReset farmReset;
    private SmpHud hud;
    private Boards boards;
    private final Navigation navigation = new Navigation();

    @Override
    public void onEnable() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), logger());
            databaseHandle = Configs.database(getDataFolder().toPath(), logger());
            milestonesHandle = Configs.milestones(getDataFolder().toPath(), logger());
        } catch (final ConfigException exception) {
            severe("smp is not starting because its configuration could not be read: "
                    + exception.getMessage());
            return;
        }

        final SmpSpec config = configHandle.get();
        track = Milestones.read(milestonesHandle.get()).track();

        // ---- refusal 1: the datapacks -------------------------------------------------------
        // A world generated without them is vanilla terrain permanently, because terrain is never
        // re-rolled once it is on disk. For the farm world that is one flat day; for Nordtal, which
        // has a spawn built on it and therefore cannot be thrown away, it is the whole season.
        final Datapacks.Result packs = Datapacks.check(config.requiredDatapacks());
        if (!packs.ok()) {
            severe("smp is not starting: " + packs.describe() + ". Datapacks are read once at server "
                    + "start, so installing them now would not change any terrain - put them in the "
                    + "level-name world's datapacks/ folder and restart.");
            return;
        }

        // ---- refusal 2: Nordtal ---------------------------------------------------------------
        worlds = new Worlds(config);
        final World nordtal = worlds.bootstrap().orElse(null);
        if (nordtal == null) {
            severe("smp is not starting: the world '" + config.worldNordtal() + "' does not exist. "
                    + "It carries the built spawn, so an empty replacement would hide a broken "
                    + "deployment behind a world nobody recognises.");
            return;
        }
        worlds.applyFixedBorders();

        // ---- refusal 3: where the balloon stands ----------------------------------------------
        final Boxes balloons = ConfigBoxes.balloons(config);
        final String placement = checkNordtalBalloon(config, balloons);
        if (placement != null) {
            severe("smp is not starting: " + placement);
            return;
        }

        pool = SmpPool.open(databaseHandle.get());
        final Jdbi jdbi = Jdbi.create(pool)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin());
        dao = jdbi.onDemand(SmpDao.class);
        identities = new Identities(dao);

        messages = Messages.load(getClass().getClassLoader(), "messages/smp",
                Locale.ENGLISH, Locale.GERMAN);
        locales = new PlayerLocales(mcUuid -> dao.discordIdOf(mcUuid)
                .map(id -> Locales.parse(dao.localeOf(id).orElse(null)))
                .orElse(Locales.DEFAULT));

        // Everything below this line touches the database, so it happens off the main thread. The
        // rule was written into this repository on 2026-09-01 and it has no exceptions.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::loadSeasonState);

        final PreGenerator pregen = PreGenerator.open(this, config.pregenerationPattern()).orElse(null);
        if (pregen == null) {
            severe("smp is not starting: Chunky is installed but did not register its API service. "
                    + "The farm world cannot be pre-generated without it, and a reset that keeps "
                    + "postponing itself looks like nothing at all.");
            return;
        }

        final FarmWorldSwap swap = new FarmWorldSwap(this, config.worldFarm(),
                config.farmWorldStagingSuffix(), config.farmWorldRetiredSuffix());
        farmReset = new FarmWorldReset(this, config, worlds, swap, pregen, messages, locales,
                dao, navigation);
        farmReset.start();

        final PlayerComposition composition =
                new PlayerComposition(new Prestige(config.prestigeThresholdHours()));
        final PlayerSurfaces surfaces = new PlayerSurfaces(this, identities, composition);

        hud = new SmpHud(this, worlds, season, navigation, messages, locales);
        hud.start();
        boards = new Boards(this, config, season, messages, locales);
        boards.start();

        // One async sweep on a timer for everything a surface reads out of the database: the active
        // milestone's progress and the aura leaderboard. Both change a few times an hour and are
        // drawn several times a second, which is the whole argument for reading them here and not
        // there.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshSurfaceData, 100L, 100L);

        final Boxes regions = ConfigBoxes.spawnRegions(config);
        getServer().getPluginManager().registerEvents(
                new JoinGate(identities, messages, logger()), this);
        getServer().getPluginManager().registerEvents(
                new PresenceListener(this, identities, surfaces, composition, config), this);
        getServer().getPluginManager().registerEvents(
                new NavigateListener(this, dao, navigation, identities, locales), this);
        getServer().getPluginManager().registerEvents(
                new ProtectionListener(regions, identities, messages, locales), this);
        getServer().getPluginManager().registerEvents(
                new BalloonListener(balloons, worlds, season, track, messages, locales), this);
        getServer().getPluginManager().registerEvents(
                new PortalGate(worlds, season, messages, locales), this);

        registerCommands();

        getLogger().info("smp enabled - " + track.size() + " milestones, "
                + regions.all().size() + " protected boxes, " + balloons.all().size() + " balloons");
    }

    @Override
    public void onDisable() {
        if (hud != null) {
            hud.stop();
        }
        if (boards != null) {
            boards.stop();
        }
        if (farmReset != null) {
            farmReset.stop();
        }
        if (pool != null) {
            pool.close();
        }
        getLogger().info("smp disabled");
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands =
                    new NavigateCommand(this, dao, navigation, identities, messages, locales);
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());
        });
    }

    /**
     * The one place a surface's data is read. <b>Async</b>, on a timer.
     *
     * <p>Both halves are cheap and both are drawn far more often than they change: the active
     * milestone's objectives move a few times an hour, the aura leaderboard a few times a day. The
     * HUD redraws four times a second and the boards every five, so reading either at the point of
     * use would be a database round trip inside a render loop.
     */
    private void refreshSurfaceData() {
        try {
            dao.activeMilestoneKey().ifPresentOrElse(
                    key -> season.refreshActive(key, dao.objectivesOf(key)),
                    () -> season.refreshActive(null, java.util.List.of()));
            boards.setLeaderboard(dao.topAura(10));
        } catch (final RuntimeException exception) {
            // The database being briefly unreachable must not kill the repeating task - the surfaces
            // keep showing what they last knew, which is the right thing for a scoreboard to do.
            getLogger().warning("could not refresh the boards and HUD: " + exception);
        }
    }

    /**
     * Reads the track's progress and puts Nordtal's border where the completed milestones say.
     *
     * <p>Runs async, then hops back for the border. Not animated: this is a restart catching up
     * with a border that moved before it, and animating it would show every player a wall crawling
     * outwards for something that happened last week.
     */
    private void loadSeasonState() {
        for (final Milestone milestone : track.milestones()) {
            dao.ensureMilestone(milestone.key(), MilestoneState.LOCKED.name());
        }
        final List<String> completed = dao.completedMilestoneKeys();
        season.refresh(completed, track);

        Bukkit.getScheduler().runTask(this, () -> {
            final int diameter = season.borderDiameter();
            if (diameter > 0) {
                worlds.expandNordtal(diameter, false);
            }
            getLogger().info("season state: " + completed.size() + " milestones complete, unlocks "
                    + season.unlocked() + ", Nordtal's border "
                    + (diameter > 0 ? String.valueOf(diameter) : "untouched"));
        });
    }

    /**
     * The one geometric rule the spawn build has to obey.
     *
     * <p>Nordtal's balloon must stand outside radius 10 and inside radius 21.5 of the border centre.
     * That is what makes the opening border of 20 withhold travel and the first expansion to 43 hand
     * it over; everything else social sits inside radius 10, so the only thing the opening minutes
     * withhold is the balloon. Get it wrong in the build and the season's first milestone means
     * nothing - which is not something a player would ever report as a bug.
     *
     * @return null when the placement is fine, or the complaint to refuse the start with
     */
    private String checkNordtalBalloon(final SmpSpec config, final Boxes balloons) {
        final List<Box> inNordtal = balloons.in(config.worldNordtal());
        if (inNordtal.isEmpty()) {
            return "no balloon is configured in '" + config.worldNordtal() + "', so nobody could "
                    + "ever leave it.";
        }
        for (final Box box : inNordtal) {
            final double distance = box.horizontalDistanceFrom(config.borderCentreX(), config.borderCentreZ());
            if (distance <= 10.0 || distance >= 21.5) {
                return String.format(Locale.ROOT,
                        "Nordtal's balloon sits at radius %.1f of the border centre %d/%d. It has to "
                                + "be outside 10 and inside 21.5, because that is what makes border "
                                + "20 withhold the farm world and the opening expansion to 43 hand "
                                + "it over.",
                        distance, config.borderCentreX(), config.borderCentreZ());
            }
        }
        return null;
    }

    private void severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
    }

    private Logger logger() {
        return LoggerFactory.getLogger(getClass());
    }
}
