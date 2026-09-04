package eu.nordtal.s2.smp;

import com.zaxxer.hikari.HikariDataSource;

import java.util.concurrent.ScheduledExecutorService;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import eu.nordtal.s2.smp.command.BukkitSmpEffects;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Locales;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.Configs;
import eu.nordtal.s2.smp.config.DatabaseSpec;
import eu.nordtal.s2.smp.config.Milestones;
import eu.nordtal.s2.smp.config.MilestonesSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.SoundsSpec;
import eu.nordtal.s2.smp.db.JoinGate;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.smp.db.SmpPool;
import eu.nordtal.s2.smp.farm.FarmWorldReset;
import eu.nordtal.s2.smp.farm.FarmWorldSwap;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneState;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.pregen.PreGenerator;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.NavigateCommand;
import eu.nordtal.s2.smp.command.SmpCommand;
import eu.nordtal.s2.smp.command.UpdateCommands;
import eu.nordtal.s2.smp.chat.SystemLines;
import eu.nordtal.s2.smp.duel.DuelListener;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.feedback.SurfaceListener;
import eu.nordtal.s2.smp.grave.GraveListener;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.navigate.NavigateListener;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.npc.NpcListener;
import eu.nordtal.s2.smp.npc.SpawnNpc;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerComposition;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.progress.AdvancementListener;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;
import eu.nordtal.s2.smp.progress.StatisticPoller;
import eu.nordtal.s2.smp.wheel.Wheel;
import eu.nordtal.s2.smp.wheel.WheelListener;
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

    /**
     * Its own file, and its own handle, so that {@code /smp reload} can re-read it.
     *
     * <p>{@code config.yml} deliberately cannot be reloaded - the plugin binds worlds, borders and
     * coordinates once at enable and would not notice them changing - and the sounds are the one
     * thing in it an operator was expected to iterate on with players online.
     */
    private ConfigHandle<SoundsSpec> soundsHandle;

    /** Held so {@code /smp reload} can swap what it answers; every listener has this one instance. */
    private SmpSounds sounds;

    private HikariDataSource pool;
    private AdminWatch adminWatch;

    /**
     * The command layer: what this server runs itself, what it sends elsewhere, and what it is
     * asked to run.
     *
     * <p>Two {@code SmpEffects} exist and the difference is the executor. {@link #chatEffects} is
     * built with the plugin's async scheduler, because a Brigadier handler runs on the main thread.
     * The inbox's is built with {@code Runnable::run}, because the inbox settles a request row when
     * the command returns - {@code CommandInbox#register} refuses the wrong one at startup rather
     * than letting it be found as an empty answer in Discord.</p>
     */
    private SmpEffects chatEffects;
    private Outbox outbox;
    private ScheduledExecutorService commandWaiter;
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
    private ObjectiveEngine engine;
    private StatisticPoller poller;
    private Graves graves;
    private Duels duels;
    private SpawnNpc npc;
    private org.bukkit.scheduler.BukkitTask heartbeat;

    /**
     * <b>One try around the whole start, and that is the point of it.</b>
     *
     * <p>The configuration read used to be the only guarded step, so anything that threw after it -
     * {@code Messages.load} on an unwritable data folder, a milestone file that parses and then
     * fails validation, a listener whose constructor disagrees with the world - escaped
     * {@code onEnable}, Paper disabled this plugin, and <b>the server carried on running without
     * it</b>. That is the exact state {@code severe} exists to prevent, and it was reachable by
     * every step but the first. Found by review, 2026-09-04, in {@code network-control} first,
     * where the same shape left the proxy accepting logins un-gated.</p>
     *
     * <p>The readiness marker makes that state visible - it is written as the last line of a start
     * that finished, so a start that did not go red within thirty seconds. Visible is not the same
     * as safe: nothing outside this JVM can act on it, Docker restarts nothing on health alone, and
     * a backend that is up and empty is a season nobody can play. Stopping is still ours to do.</p>
     *
     * <p>{@code RuntimeException} only, because {@code ConfigException} is checked and
     * {@code start()} already answers it where it is thrown - the one step that was guarded before
     * is the one step that keeps its own guard.</p>
     */
    @Override
    public void onEnable() {
        try {
            start();
        } catch (final RuntimeException failure) {
            severe("smp is not starting: " + failure.getMessage());
        }
    }

    /** Everything a start consists of. Throws rather than half-starting; see {@link #onEnable()}. */
    private void start() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), logger());
            databaseHandle = Configs.database(getDataFolder().toPath(), logger());
            milestonesHandle = Configs.milestones(getDataFolder().toPath(), logger());
            soundsHandle = Configs.sounds(getDataFolder().toPath(), logger());
        } catch (final ConfigException exception) {
            severe("smp is not starting because its configuration could not be read: "
                    + exception.getMessage());
            return;
        }

        final SmpSpec config = configHandle.get();
        track = Milestones.read(milestonesHandle.get()).track();

        // The sound vocabulary, read once. A key that is wrong is reported here and silences its
        // own category; it deliberately does not join the refusals below, because a typo in a chime
        // is not worth a season offline and the console line says exactly what was ignored.
        final SmpSounds sounds = SmpSounds.of(soundsHandle.get(), getLogger()::warning);
        this.sounds = sounds;

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

        // Two roots: :commands' shared bundle underneath this module's own. What a shared mechanism
        // says has to say the same thing on every surface, and the confirmation line is the first
        // of those to reach this plugin. This module's own keys win on a collision.
        messages = Messages.load(getClass().getClassLoader(),
                java.util.List.of("messages/commands", "messages/smp"),
                getDataFolder().toPath().resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        reportUnknownOverrides();
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
        // The HUD is built before the reset and not after it: the four reset warnings take the
        // status bar over for eight seconds each, which is the half of "chat + HUD" that reaches
        // somebody mining with chat closed. Nothing else in this method depends on the order.
        hud = new SmpHud(this, worlds, season, navigation, messages, locales);
        hud.start();

        farmReset = new FarmWorldReset(this, config, worlds, swap, pregen, messages, locales,
                dao, navigation, sounds, hud);
        farmReset.start();

        // One instance, registered as a listener and handed to everything that has a moment: it
        // has to be the same object that stamped a rocket and the one asked whether that rocket may
        // hurt anybody (WorldEffects#onDamage).
        final WorldEffects effects = new WorldEffects(this);
        getServer().getPluginManager().registerEvents(effects, this);

        final PlayerComposition composition =
                new PlayerComposition(new Prestige(config.prestigeThresholdHours()));
        final PlayerSurfaces surfaces =
                new PlayerSurfaces(this, identities, composition, new MessageRenderer(messages));

        boards = new Boards(this, config, season, messages, locales);
        boards.start();

        // One async sweep on a timer for everything a surface reads out of the database: the active
        // milestone's progress and the aura leaderboard. Both change a few times an hour and are
        // drawn several times a second, which is the whole argument for reading them here and not
        // there.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshSurfaceData, 100L, 100L);

        final Boxes regions = ConfigBoxes.spawnRegions(config);

        // Admins are operators for as long as they are admins. The sweep runs here, before a single
        // join can be handled: ops.json is persistent, so anybody left in it by a crash or a
        // SIGKILL would otherwise still be an operator on this start. AdminOperators carries the
        // whole reasoning, including why it asks the database nothing.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();

        // One instance, shared with the watcher below: the gate fills the admin flag at pre-login
        // and the watcher keeps it in step while people are online. Two instances would be two
        // caches, and the one the fullness check reads would be the stale one.
        final FullServerAdmission admission = new FullServerAdmission();

        getServer().getPluginManager().registerEvents(
                new JoinGate(identities, admission, messages, logger()), this);
        getServer().getPluginManager().registerEvents(
                new PresenceListener(this, identities, surfaces, composition, config,
                        messages, locales, operators), this);
        getServer().getPluginManager().registerEvents(
                new SystemLines(identities, composition, messages, locales), this);
        getServer().getPluginManager().registerEvents(
                new NavigateListener(this, dao, navigation, identities, locales, sounds), this);

        // ...and keeps being one only for as long as the database says so. Without this the flag is
        // read once per session and a revoked admin keeps operator until they disconnect; see
        // AdminWatch.
        //
        // This is the one module that passes an extra cache: Identities holds the admin flag for
        // the six-element composition, so the admin tag on a nametag every other player can see is
        // drawn from it. The redraw is conditional because a redraw of every surface on a
        // thirty-second timer, for the length of a season, is work nobody would ever notice going in.
        adminWatch = new AdminWatch(this, eu.nordtal.s2.common.access.AccessDirectory.using(pool),
                operators, admission,
                admins -> {
                    if (identities.recordAdmins(admins)) {
                        surfaces.refreshAll();
                    }
                },
                logger());

        // ---- block 3: the activities -----------------------------------------------------
        engine = new ObjectiveEngine(this, dao, track, season, worlds, identities, messages,
                locales, config, sounds, effects);
        poller = new StatisticPoller(this, track, engine, identities);
        poller.start();

        graves = new Graves(this, dao, messages, locales, sounds, effects);
        duels = new Duels(this, dao, config, worlds, identities, messages, locales, sounds,
                effects);

        final DeathPenalty penalty = new DeathPenalty(config.deathPenalty(),
                config.deathPenaltyListed(), java.util.Set.copyOf(config.deathCausesListed()));
        final Wheel wheel = new Wheel(this, dao, config, identities, messages, locales, sounds);

        getServer().getPluginManager().registerEvents(
                new AdvancementListener(this, dao, engine, identities, config, messages, locales,
                        sounds), this);
        getServer().getPluginManager().registerEvents(
                new GraveListener(this, dao, graves, identities, penalty, duels::isInArena,
                        messages, locales, sounds), this);
        getServer().getPluginManager().registerEvents(new DuelListener(config, duels), this);

        // The figure in the tavern, and the only way a HAND_IN objective can be fulfilled.
        npc = new SpawnNpc(this, config);
        npc.spawn();
        getServer().getPluginManager().registerEvents(
                new NpcListener(this, dao, npc, track, engine, identities, messages, locales,
                        sounds), this);
        getServer().getPluginManager().registerEvents(
                new WheelListener(ConfigBoxes.wheelRegions(config), wheel), this);

        // A grave in the farm world dies with the daily reset, like everything else there. That is
        // intended and announced, and it is the one real risk of going there.
        farmReset.onWorldReplaced(world -> {
            graves.forgetWorld(world);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> dao.deleteGravesIn(world));
        });

        // Graves outlive a restart, which is most of what "the grave stands forever" means in
        // practice. Read them back once the world is up.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            final var rows = dao.openGraves();
            Bukkit.getScheduler().runTask(this, () -> graves.restore(rows));
        });
        getServer().getPluginManager().registerEvents(
                new ProtectionListener(regions, identities, messages, locales, sounds), this);
        getServer().getPluginManager().registerEvents(
                new BalloonListener(balloons, worlds, season, track, messages, locales, sounds,
                        effects), this);
        getServer().getPluginManager().registerEvents(
                new PortalGate(worlds, season, messages, locales, sounds), this);

        // One listener for SURFACE_OPEN and SURFACE_CLOSE across every menu this plugin opens - see
        // Surface. The grave inventory has a null holder and is recognised by identity, which is
        // what the predicate is for.
        getServer().getPluginManager().registerEvents(
                new SurfaceListener(sounds, graves::isShowingGrave), this);

        // ---- block 4: the commands ------------------------------------------------------
        //
        // Built after the activities because half of what /smp does goes through the objective
        // engine, and started before the admin watch because the inbox rides on that watch's
        // LISTEN connection - one connection carrying nordtal_admin and nordtal_command, which is
        // what NotificationListener was built for.
        final eu.nordtal.s2.common.access.AccessDirectory access =
                eu.nordtal.s2.common.access.AccessDirectory.using(pool);
        chatEffects = new BukkitSmpEffects(this, BukkitSmpEffects.async(this), dao, engine,
                farmReset, identities, access, this::reloadTrack);

        commandWaiter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        final eu.nordtal.s2.common.command.CommandRequests requests =
                eu.nordtal.s2.common.command.CommandRequests.borrowing(pool);
        outbox = new Outbox(requests, commandWaiter,
                (message, failure) -> getLogger()
                        .log(java.util.logging.Level.WARNING, message, failure));

        final PaperCommandInbox inbox = new PaperCommandInbox(this, Target.SMP, requests, access);
        // Inline, on purpose - see the field comment.
        final SmpEffects inboxEffects = new BukkitSmpEffects(this, Runnable::run, dao, engine,
                farmReset, identities, access, this::reloadTrack);
        SmpCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.start(this);

        registerCommands(sounds);

        adminWatch.start(java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()),
                config.adminListenEnabled()
                        ? new AdminWatch.DatabaseConnection(databaseHandle.get().jdbcUrl(),
                                databaseHandle.get().username(), databaseHandle.get().password(),
                                databaseHandle.get().queryTimeoutSeconds())
                        : null,
                inbox.refreshes(), inbox.channels());

        startHeartbeat();

        getLogger().info("smp enabled - " + track.size() + " milestones, "
                + regions.all().size() + " protected boxes, " + balloons.all().size() + " balloons");
    }

    /**
     * The container readiness marker - see {@link Readiness}, and note where this call sits.
     *
     * <p>It is the <b>last</b> thing {@code start()} does, and so the last thing a successful
     * {@code onEnable} reaches, because that is the entire rule: all
     * four refusals above return before reaching it, so a marker on disk means this plugin got all
     * the way through - which is precisely the state the first deployment could not tell apart from
     * a Paper server with no season on it. Written from Bukkit's async scheduler, which is also
     * deliberate - a repeating async task is re-queued by the main-thread heartbeat, so a server
     * frozen mid-tick stops beating and the container goes stale rather than staying green on an
     * open port.</p>
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
        if (npc != null) {
            npc.remove();
        }
        if (duels != null) {
            duels.stop();
        }
        if (graves != null) {
            graves.clearDisplays();
        }
        if (poller != null) {
            poller.stop();
        }
        if (hud != null) {
            hud.stop();
        }
        if (boards != null) {
            boards.stop();
        }
        if (farmReset != null) {
            farmReset.stop();
        }
        // Before the pool: the listener thread is parked on a connection of its own, but a refresh
        // already in flight reads through the pool.
        if (adminWatch != null) {
            adminWatch.close();
        }
        if (commandWaiter != null) {
            // Before the pool: a wait in flight reads the request row through it.
            commandWaiter.shutdownNow();
        }
        if (pool != null) {
            pool.close();
        }
        getLogger().info("smp disabled");
    }

    private void registerCommands(final SmpSounds sounds) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands =
                    new NavigateCommand(this, dao, navigation, identities, messages, locales, sounds);
            // Not folded into :commands, deliberately: /navigate opens an inventory and /poi add
            // reads the caller's position. Both are commands about being somewhere, and a Discord
            // half of either would be a different command wearing the same name.
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());

            SmpCommand.build(this, messages, locales, identities, sounds, outbox, chatEffects,
                            // Over the pool this plugin already owns. The updater is a different
                            // container and this is how it is reached: a row and a notification,
                            // never a call.
                            new UpdateCommands(this, UpdateDirectory.using(pool), messages, locales))
                    .forEach(node -> event.registrar().register(node));
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
            final java.util.Optional<String> active = dao.activeMilestoneKey();
            active.ifPresentOrElse(
                    key -> season.refreshActive(key, dao.objectivesOf(key)),
                    () -> season.refreshActive(null, java.util.List.of()));
            poller.setActiveMilestone(active);
            boards.setLeaderboard(dao.topAura(10));
        } catch (final RuntimeException exception) {
            // The database being briefly unreachable must not kill the repeating task - the surfaces
            // keep showing what they last knew, which is the right thing for a scoreboard to do.
            getLogger().warning("could not refresh the boards and HUD: " + exception);
        }
    }

    /**
     * Re-reads {@code milestones.yml} while players are online.
     *
     * <p>One of the concept's three escape hatches: a target lowered below its collected progress
     * completes the objective at once and pays it out, scaled to what was actually reached. Only the
     * track is re-read - never the duel loadouts or the database password - which is why it is a
     * separate file in the first place.
     */
    private void reloadTrack() {
        // Three files, three reports, three independent failures - see the comment below. The
        // sounds go first because they are the cheapest thing to get wrong and the only one an
        // operator is expected to be iterating on while somebody waits to hear the result.
        try {
            soundsHandle.reload();
            sounds.reload(soundsHandle.get());
            getLogger().info("the sounds were reloaded");
        } catch (final ConfigException | RuntimeException exception) {
            getLogger().severe("the sounds could not be reloaded, the running ones are unchanged: "
                    + exception.getMessage());
        }

        try {
            milestonesHandle.reload();
            track = Milestones.read(milestonesHandle.get()).track();
            Bukkit.getScheduler().runTaskAsynchronously(this, this::loadSeasonState);
            getLogger().info("the milestone track was reloaded: " + track.size() + " milestones");
        } catch (final ConfigException | RuntimeException exception) {
            getLogger().severe("the milestone track could not be reloaded, the running one is "
                    + "unchanged: " + exception.getMessage());
        }

        // The wording is reloaded in the same breath and reported separately, because the two fail
        // independently: a broken milestones.yml must not stop a corrected message from arriving,
        // and a typo'd override must not read as a track that failed to load.
        try {
            messages.reload();
            reportUnknownOverrides();
            getLogger().info("the message bundles were reloaded");
        } catch (final RuntimeException exception) {
            getLogger().severe("the messages could not be reloaded, the running ones are "
                    + "unchanged: " + exception.getMessage());
        }
    }

    /**
     * Names every override entry that overrode nothing.
     *
     * <p>An override for a key no bundle declares is stored and never looked up, so the failure is
     * a line that does not change and no error anywhere. Naming them in the console is the only
     * place the difference between "my override is wrong" and "my override is ignored" is visible.
     */
    private void reportUnknownOverrides() {
        messages.unknownOverrideKeys().forEach(key -> getLogger().warning(
                "the message override names " + key + ", which no bundle declares - it is stored"
                        + " and never used; check the spelling"));
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

    /**
     * The plugin cannot run, so neither can this server.
     *
     * <h2>Why it takes the server with it, since 2026-09-02</h2>
     * It used to log and call {@code disablePlugin} alone, which is the convention this repository
     * states for {@code papermc-display-tags} - a plugin on somebody else's server, where "the
     * plugin goes down, the server keeps running" is plainly right. On our own dedicated backends it
     * is plainly wrong, and the first deployment showed what it costs: four nested config
     * interfaces without {@code @ConfigSpec} made the first write of {@code config.yml} throw, this
     * plugin disabled itself, and Paper carried on. The container stayed up, its healthcheck stayed
     * green, and what was left was a Minecraft server with no season on it - which nothing about
     * looks wrong until somebody joins.
     *
     * <p>No check outside the JVM could tell that state from a healthy one when this rule was
     * written. The jars are all in the folder, so the entrypoint's guard passes; the port was open,
     * so the port check passed. The only place the difference was knowable is here, which is why the
     * answer is here.</p>
     *
     * <p>Since 2026-09-04 the container does report it, because {@link #startHeartbeat()} is below
     * every refusal and its marker is never written on this path. That does not soften the rule: an
     * unhealthy container is a red square in Arcane and nothing else - Docker restarts nothing on
     * health alone - so without the shutdown the server would still be up, still accepting players,
     * and merely honest about it.</p>
     *
     * <p>{@code disablePlugin} first and then {@code shutdown}: the disable runs whatever cleanup
     * {@code onDisable} does, and if the shutdown were ever ignored the plugin is still off rather
     * than half-enabled.</p>
     */
    private void severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
    }

    private Logger logger() {
        return LoggerFactory.getLogger(getClass());
    }


}
