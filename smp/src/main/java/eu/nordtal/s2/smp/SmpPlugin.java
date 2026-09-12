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
import eu.nordtal.s2.smp.milestone.StoredProgress;
import eu.nordtal.s2.smp.milestone.TrackValidation;
import eu.nordtal.s2.smp.pregen.PreGenerator;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.NavigateCommand;
import eu.nordtal.s2.smp.command.SmpCommand;
import eu.nordtal.s2.papercommon.command.UpdateWatcher;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.papercommon.stage.BukkitCinematics;
import eu.nordtal.s2.smp.duel.DuelListener;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.feedback.SurfaceListener;
import eu.nordtal.s2.smp.grave.GraveListener;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.headstart.HeadStart;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.navigate.NavigateListener;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.npc.NpcListener;
import eu.nordtal.s2.smp.npc.NpcProtection;
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
import eu.nordtal.s2.smp.welcome.SeasonWelcome;
import eu.nordtal.s2.smp.travel.BalloonDisplay;
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
 * <p>Wiring and startup refusals. The refusals are the interesting part: each stops the plugin
 * rather than letting it run degraded, because each produces damage that cannot be undone.
 */
public final class SmpPlugin extends JavaPlugin {

    private ConfigHandle<SmpSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
    private ConfigHandle<MilestonesSpec> milestonesHandle;

    /**
     * Its own file, and its own handle, so that {@code /smp reload} can re-read it.
     *
     * <p>{@code config.yml} deliberately cannot be reloaded: the plugin binds worlds, borders and
     * coordinates once at enable and would not notice them changing.
     */
    private ConfigHandle<SoundsSpec> soundsHandle;

    /** Held so {@code /smp reload} can swap what it answers; every listener has this one instance. */
    private SmpSounds sounds;

    private HikariDataSource pool;
    private AdminWatch adminWatch;

    /** What a non-admin may type here, and what their client is told exists. */
    private eu.nordtal.s2.papercommon.command.CommandFilter commandFilter;

    /**
     * The command layer: what this server runs itself, what it sends elsewhere, and what it is
     * asked to run.
     *
     * <p>Two {@code SmpEffects} exist and the difference is the executor. {@link #chatEffects} uses
     * the plugin's async scheduler, because a Brigadier handler runs on the main thread; the
     * inbox's uses {@code Runnable::run}, because the inbox settles a request row when the command
     * returns. {@code CommandInbox#register} refuses the wrong one at startup.</p>
     */
    private SmpEffects chatEffects;
    private Outbox outbox;
    private ScheduledExecutorService commandWaiter;
    private SmpDao dao;
    private Jdbi jdbi;
    private Messages messages;
    private eu.nordtal.s2.common.command.CommandRequests requests;
    private eu.nordtal.s2.smp.announce.Announcer announcer;
    /**
     * What the last {@code /smp reload} refused the file for, or empty when it took it.
     *
     * <p>Read by the reload command so the answer names the disagreement rather than only saying
     * that something went wrong.</p>
     */
    private volatile List<String> trackProblems = List.of();

    /** {@code :commands}' bundle as the inbox renders it - a second view of the same files. */
    private Messages sharedMessages;
    private PlayerLocales locales;

    /**
     * The milestone track, replaced by {@code /smp reload}.
     *
     * <p><b>volatile</b>, because the write and the reads are on different threads:
     * {@code reloadTrack} runs on Bukkit's async executor and the suggestion supplier reads it on
     * the server thread, once per keystroke.</p>
     */
    private volatile MilestoneTrack track;
    private Worlds worlds;
    private final SeasonState season = new SeasonState();
    private Identities identities;
    private FarmWorldReset farmReset;
    /** The daily ask for a network backup. Null-safe stop: it is built in onEnable. */
    private eu.nordtal.s2.smp.backup.NightlyBackup nightlyBackup;
    private SmpHud hud;
    private Boards boards;
    private final Navigation navigation = new Navigation();
    private ObjectiveEngine engine;
    private StatisticPoller poller;
    private Graves graves;
    private Duels duels;
    private SpawnNpc npc;
    /** The staging device - see BukkitCinematics. Stopped at disable, while players are still here. */
    private BukkitCinematics cinematics;
    private BalloonDisplay balloonDisplay;
    private org.bukkit.scheduler.BukkitTask heartbeat;

    /**
     * <b>One try around the whole start, and that is the point of it.</b>
     *
     * <p>Anything that throws anywhere in the start would otherwise escape {@code onEnable}, Paper
     * would disable this plugin, and <b>the server would carry on running without it</b> - which is
     * the exact state {@code severe} exists to prevent.
     *
     * <p>The readiness marker makes that state visible but not safe: nothing outside this JVM acts
     * on it, and Docker restarts nothing on health alone. Stopping is still ours to do.</p>
     *
     * <p>{@code RuntimeException} only, because {@code ConfigException} is checked and
     * {@code start()} already answers it where it is thrown - the one step that was guarded before
     * is the one step that keeps its own guard.</p>
     */
    @Override
    public void onEnable() {
        // Before anything else, and it has to be here: this loads the class every disable step
        // below goes through, while the jar it lives in still exists. See Shutdown#warmUp.
        eu.nordtal.s2.common.health.Shutdown.warmUp();
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

        // The sound vocabulary, read once. A wrong key is reported and silences its own category
        // rather than joining the refusals below: a typo in a chime is not worth a season offline.
        final SmpSounds sounds = SmpSounds.of(soundsHandle.get(), getLogger()::warning);
        this.sounds = sounds;

        // ---- refusal 1: the datapacks -------------------------------------------------------
        // A world generated without them is vanilla terrain permanently, because terrain is never
        // re-rolled once it is on disk. For Nordtal, which cannot be thrown away, that is the whole
        // season.
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

        // Not a refusal: a first join that cannot be placed leaves the player where the server
        // spawned them, which is what every first join did before the setting existed. It is a
        // warning HERE rather than only in SeasonWelcome because the alternative is finding out on
        // the first new player of the season - Configs.validate can only ask whether the key names
        // something, since the three worlds above do not exist until bootstrap has run.
        if (Bukkit.getWorld(config.firstJoinSpawn().world()) == null) {
            getLogger().warning("first-join-spawn names the world '"
                    + config.firstJoinSpawn().world() + "', which does not exist on this server. "
                    + "First joins will not be moved anywhere. The build world is called '"
                    + config.worldNordtal() + "'.");
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
        jdbi = Jdbi.create(pool)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin());
        dao = jdbi.onDemand(SmpDao.class);
        identities = new Identities(dao);

        // Three roots, most general first, so a shared mechanism says the same thing on every
        // surface. Later roots win, so this module's own keys beat both - the mechanism for
        // rewording a shared line here, not a way of adding one.
        messages = Messages.load(getClass().getClassLoader(),
                java.util.List.of("messages/paper-common", "messages/commands", "messages/smp"),
                getDataFolder().toPath().resolve("messages"), Locale.ENGLISH, Locale.GERMAN);
        reportUnknownOverrides();
        locales = new PlayerLocales(mcUuid -> dao.discordIdOf(mcUuid)
                .map(id -> Locales.parse(dao.localeOf(id).orElse(null)))
                .orElse(Locales.DEFAULT));

        // Everything below this line touches the database, so it happens off the main thread.
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
        // The HUD is built before the reset: the four reset warnings take the status bar over, and
        // that is the half of the warning that reaches somebody mining with chat closed.
        hud = new SmpHud(this, worlds, season, navigation, messages, locales);
        hud.start();

        // The SMP's line into Discord: one command_request row per language, fire and forget.
        // Built before the two things that have a moment to announce.
        requests = eu.nordtal.s2.common.command.CommandRequests.borrowing(pool);
        announcer = new eu.nordtal.s2.smp.announce.Announcer(requests, messages,
                BukkitSmpEffects.async(this),
                (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));

        farmReset = new FarmWorldReset(this, config, worlds, swap, pregen, messages, locales,
                dao, navigation, sounds, hud, announcer);
        farmReset.start();

        // The network's backup clock, here because the updater must not schedule its own work -
        // `serve` is not a scheduler - and this is the one process that already runs a daily clock.
        // It writes an update_request row and nothing else.
        nightlyBackup = new eu.nordtal.s2.smp.backup.NightlyBackup(this,
                UpdateDirectory.using(pool), BukkitSmpEffects.async(this), config.backupTime());
        nightlyBackup.start();

        // One instance: the object that stamped a rocket has to be the one asked whether that
        // rocket may hurt anybody (WorldEffects#onDamage).
        final WorldEffects effects = new WorldEffects(this);
        getServer().getPluginManager().registerEvents(effects, this);

        final PlayerComposition composition =
                new PlayerComposition(new Prestige(config.prestigeThresholdHours()));
        final PlayerSurfaces surfaces =
                new PlayerSurfaces(this, identities, composition, new MessageRenderer(messages));

        boards = new Boards(this, config, season, messages, locales);
        boards.start();

        // One async sweep on a timer for everything a surface reads out of the database: both
        // halves change a few times an hour and are drawn several times a second.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refreshSurfaceData, 100L, 100L);
        // The one main-thread read of the player collection, for /smp status - see the field.
        Bukkit.getScheduler().runTaskTimer(this, () -> online = Bukkit.getOnlinePlayers().size(),
                20L, 20L);

        final Boxes regions = ConfigBoxes.spawnRegions(config);

        // Admins are operators for as long as they are admins. The sweep runs before a single join
        // can be handled: ops.json is persistent, so anybody left in it by a crash would otherwise
        // still be an operator on this start.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();

        // One instance, shared with the watcher below: two would be two caches, and the one the
        // fullness check reads would be the stale one.
        final FullServerAdmission admission = new FullServerAdmission();

        getServer().getPluginManager().registerEvents(
                new JoinGate(identities, admission, messages, logger()), this);
        // The composition is this server's half of the shared lines; everything around it is
        // :paper-common's and is the same on every backend.
        final SystemLines systemLines = new SystemLines(
                player -> composition.chatPrefix(player.getName(),
                        identities.of(player.getUniqueId())),
                messages, locales);

        // Registered as a listener because a staging ends when the player leaves or dies, and
        // stopped at disable because Paper disables plugins before it saves players - a blindness
        // still running then would be written to disk with them.
        cinematics = new BukkitCinematics(this, sounds::play);
        getServer().getPluginManager().registerEvents(cinematics, this);
        final SeasonWelcome welcome =
                new SeasonWelcome(this, dao, identities, locales, cinematics, config, worlds);

        getServer().getPluginManager().registerEvents(
                new PresenceListener(this, identities, surfaces, locales, operators,
                        systemLines, welcome), this);
        getServer().getPluginManager().registerEvents(systemLines, this);
        getServer().getPluginManager().registerEvents(
                new NavigateListener(this, dao, navigation, identities, locales, sounds), this);
        // The start event's winner is paid on their FIRST join here, and never by hunger-games -
        // see HeadStart for why the dependency points this way round.
        getServer().getPluginManager().registerEvents(
                new HeadStart(this, dao, identities, surfaces, config, messages, locales, sounds),
                this);

        // ...and keeps being one only for as long as the database says so; without this a revoked
        // admin keeps operator until they disconnect.
        //
        // The extra cache is Identities, which holds the admin flag for the player composition, so
        // the admin tag on a nametag is drawn from it. The redraw is conditional so an unchanged
        // roster costs nothing on every tick of the timer.
        adminWatch = new AdminWatch(this, eu.nordtal.s2.common.access.AccessDirectory.using(pool),
                operators, admission,
                admins -> {
                    if (identities.recordAdmins(admins)) {
                        surfaces.refreshAll();
                    }
                },
                logger());

        // ---- block 3: the activities -----------------------------------------------------
        engine = new ObjectiveEngine(this, dao, () -> track, season, worlds, identities, messages,
                locales, config, sounds, effects, announcer);
        poller = new StatisticPoller(this, () -> track, engine, identities);
        poller.start();

        graves = new Graves(this, dao, identities, messages, locales, sounds, effects);
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
        getServer().getPluginManager().registerEvents(new DuelListener(this, config, duels), this);

        // The figure in the tavern, and the only way a HAND_IN objective can be fulfilled.
        npc = new SpawnNpc(this, config);
        npc.spawn();
        getServer().getPluginManager().registerEvents(
                new NpcListener(this, dao, npc, () -> track, engine, identities,
                        config::wheelExtraSpinPercents, messages, locales, sounds), this);
        // Separate from NpcListener: that one is what the figure is FOR, this one keeps it
        // standing. Invulnerable survives neither a creative-mode hit nor the void, and the spawn
        // protection covers blocks rather than entities.
        getServer().getPluginManager().registerEvents(new NpcProtection(npc), this);
        getServer().getPluginManager().registerEvents(
                new WheelListener(ConfigBoxes.wheelRegions(config), wheel), this);

        // A grave in the farm world dies with the daily reset, like everything else there.
        farmReset.onWorldReplaced(world -> {
            graves.forgetWorld(world);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> dao.deleteGravesIn(world));
        });

        // Graves outlive a restart, so they are read back once the world is up.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            final var rows = dao.openGraves();
            Bukkit.getScheduler().runTask(this, () -> graves.restore(rows));
        });
        getServer().getPluginManager().registerEvents(
                new ProtectionListener(regions, identities, messages, locales, sounds), this);
        getServer().getPluginManager().registerEvents(
                new BalloonListener(balloons, worlds, season, () -> track, messages, locales, sounds,
                        effects), this);
        // The balloon a player sees, as opposed to the box they step into: one item display per
        // configured box, wearing the pack's model.
        balloonDisplay = new BalloonDisplay(this, balloons);
        balloonDisplay.spawn();
        getServer().getPluginManager().registerEvents(
                new PortalGate(this, worlds, season, messages, locales, sounds), this);

        // One listener for SURFACE_OPEN and SURFACE_CLOSE across every menu this plugin opens. The
        // grave inventory has a null holder and is recognised by identity, hence the predicate.
        getServer().getPluginManager().registerEvents(
                new SurfaceListener(sounds, graves::isShowingGrave), this);

        // ---- block 4: the commands ------------------------------------------------------
        //
        // Built after the activities because half of what /smp does goes through the objective
        // engine, and started before the admin watch because the inbox rides on that watch's
        // LISTEN connection - one connection carrying nordtal_admin and nordtal_command.
        final eu.nordtal.s2.common.access.AccessDirectory access =
                eu.nordtal.s2.common.access.AccessDirectory.using(pool);
        chatEffects = new BukkitSmpEffects(this, BukkitSmpEffects.async(this), jdbi, dao, engine,
                farmReset, identities, access, this::reloadTrack, this::status);

        commandWaiter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        outbox = new Outbox(requests, commandWaiter,
                (message, failure) -> getLogger()
                        .log(java.util.logging.Level.WARNING, message, failure));

        // Built here rather than inside the inbox so /smp reload can replace it; one that never
        // reloaded would answer a Discord admin with the wording this process started with.
        sharedMessages = PaperCommandInbox.sharedBundle(this);
        final PaperCommandInbox inbox =
                new PaperCommandInbox(this, Target.SMP, requests, access, sharedMessages);
        // Inline, on purpose - see the field comment.
        final SmpEffects inboxEffects = new BukkitSmpEffects(this, Runnable::run, jdbi, dao, engine,
                farmReset, identities, access, this::reloadTrack, this::status);
        SmpCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.start(this);

        registerCommands(sounds);

        // The command allowlist. The proxy's refusal is the enforcement; this is the half the proxy
        // cannot do - what this server tells a client exists at all. CommandFilter fails OPEN and
        // says so when no list has been published yet.
        commandFilter = new eu.nordtal.s2.papercommon.command.CommandFilter(this,
                eu.nordtal.s2.papercommon.command.CommandFilter.Source.of(
                        eu.nordtal.s2.common.command.AllowlistDirectory.using(pool)),
                adminWatch::isAdmin, locales, messages, logger());
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

        startHeartbeat();

        getLogger().info("smp enabled - " + track.size() + " milestones, "
                + regions.all().size() + " protected boxes, " + balloons.all().size() + " balloons");
    }

    /**
     * The container readiness marker - see {@link Readiness}, and note where this call sits.
     *
     * <p>It is the <b>last</b> thing {@code start()} does: every refusal above returns before
     * reaching it, so a marker on disk means this plugin got all the way through. Written from
     * Bukkit's async scheduler, because a repeating async task is re-queued by the main-thread
     * heartbeat - a server frozen mid-tick therefore goes stale rather than staying green on an
     * open port.</p>
     */
    private void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    /**
     * Hands over any prize whose animation is still running. <b>Main thread, at disable.</b>
     *
     * <p>{@code WheelGui#finish} is a one-shot latch, so a wheel that has already paid is a no-op
     * here, and one the player closes a tick later cannot pay twice.
     */
    private void payOutSpinsInFlight() {
        for (final org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder()
                    instanceof eu.nordtal.s2.smp.wheel.WheelGui wheel) {
                wheel.finish(player, false);
            }
        }
    }

    @Override
    public void onDisable() {
        // Stops the beat, so a server going down stops claiming to be up. The marker is
        // deliberately not deleted: going stale is the signal.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        // Before anything else that could throw, and long before the pool: a wheel still spinning
        // when the server goes down pays out now or never.
        //
        // Paper disables plugins BEFORE it saves and disconnects players, so the InventoryCloseEvent
        // that normally finishes a spin never arrives and the animation's chain stops at its next
        // tick - with the spin already spent in SQL. Here the player is still online, and the save
        // that follows writes the prize to disk.
        quietly("wheel.payOutInFlight", this::payOutSpinsInFlight);
        // Before anything else that touches players: a staging still running holds a potion effect
        // on somebody who is about to be saved to disk.
        if (cinematics != null) {
            quietly("cinematics.stop", cinematics::stop);
        }
        if (npc != null) {
            quietly("npc.remove", npc::remove);
        }
        if (balloonDisplay != null) {
            quietly("balloonDisplay.remove", balloonDisplay::remove);
        }
        if (duels != null) {
            quietly("duels.stop", duels::stop);
        }
        if (graves != null) {
            quietly("graves.clearDisplays", graves::clearDisplays);
        }
        if (poller != null) {
            quietly("poller.stop", poller::stop);
        }
        if (hud != null) {
            quietly("hud.stop", hud::stop);
        }
        if (boards != null) {
            quietly("boards.stop", boards::stop);
        }
        if (farmReset != null) {
            quietly("farmReset.stop", farmReset::stop);
        }
        // Its own guard rather than farmReset's: a throw between the two would leave this null
        // while farmReset is not.
        if (nightlyBackup != null) {
            quietly("nightlyBackup.stop", nightlyBackup::stop);
        }
        // Before the pool: the listener thread is parked on a connection of its own, but a refresh
        // already in flight reads through the pool.
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
        getLogger().info("smp disabled");
    }

    /** One disable step, isolated from the next - see {@link eu.nordtal.s2.common.health.Shutdown}. */
    private void quietly(final String what, final Runnable step) {
        eu.nordtal.s2.common.health.Shutdown.quietly(what, step,
                (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    private void registerCommands(final SmpSounds sounds) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands =
                    new NavigateCommand(this, dao, navigation, identities, messages, locales, sounds);
            // Not folded into :commands: /navigate opens an inventory and /poi add reads the
            // caller's position, so a Discord half of either would be a different command wearing
            // the same name.
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());

            SmpCommand.build(this, messages, locales, identities, sounds, outbox, chatEffects,
                            // The updater is a different container and this is how it is reached:
                            // a row and a notification, never a call.
                            new UpdateWatcher(this, UpdateDirectory.using(pool)),
                            // A supplier and not the field: /smp reload replaces it.
                            () -> track, season)
                    .forEach(node -> event.registrar().register(node));
        });
    }

    /**
     * The one place a surface's data is read. <b>Async</b>, on a timer.
     *
     * <p>Both halves are drawn far more often than they change, so reading either at the point of
     * use would put a database round trip inside a render loop.
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
            // The database being briefly unreachable must not kill the repeating task; the
            // surfaces keep showing what they last knew.
            getLogger().warning("could not refresh the boards and HUD: " + exception);
        }
    }

    /**
     * Re-reads {@code milestones.yml} while players are online.
     *
     * <p>A target lowered below its collected progress completes the objective at once and pays it
     * out. Only the track is re-read, never the duel loadouts or the database password.
     */
    /**
     * Finishes every objective the reloaded targets have already been reached by. <b>Async.</b>
     *
     * <p>Without this a lowered target only takes effect the next time somebody hands something in
     * - and for an objective that has become impossible there is no next time, which is why the
     * target was lowered in the first place.
     *
     * <p>{@code finishObjective} guards itself in SQL, so running this on every reload is safe and
     * a reload that changed nothing does nothing. {@code completedBy} is null: a target moved by
     * hand has no player standing behind it.
     */
    private void completeWhateverTheNewTargetsAlreadyReach() {
        dao.activeMilestoneKey().ifPresent(milestoneKey -> dao.objectivesOf(milestoneKey).stream()
                .filter(row -> !row.completed())
                .filter(row -> row.amount() >= row.target())
                .forEach(row -> {
                    getLogger().info("objective '" + row.key() + "' is already at " + row.amount()
                            + " of its new target " + row.target() + " - completing it now");
                    engine.finishObjective(milestoneKey, row, null);
                }));
    }

    private List<String> reloadTrack() {
        // Three files, three reports, three independent failures. The sounds go first because they
        // are the one an operator is expected to be iterating on while somebody waits.
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
            final MilestoneTrack candidate = Milestones.read(milestonesHandle.get()).track();

            // "May this file replace the running one?" - asked against the rows, which is the only
            // place the answer lives: a renamed milestone key orphans everything recorded against
            // it, a changed type carries progress that now means something else, and a completed
            // objective whose target moved rewrites arithmetic already in the aura ledger.
            final List<TrackValidation.Problem> problems = TrackValidation.validate(candidate,
                    new StoredProgress(dao.storedMilestones(), dao.storedObjectives()));
            if (!problems.isEmpty()) {
                getLogger().severe("the milestone track was NOT reloaded - the file disagrees with"
                        + " progress this season has already recorded, and the running track is"
                        + " unchanged:");
                problems.forEach(problem -> getLogger().severe("  " + problem));
                trackProblems = problems.stream().map(TrackValidation.Problem::toString).toList();
                // No early return: the three fail independently, and a milestones.yml somebody is
                // still fixing must not hold back a corrected message.
            } else {
                // The rows first, the track second: if ensureRows throws halfway, the catch below
                // reports that the running track is unchanged, which is only true while the
                // assignment comes afterwards.
                ensureRows(candidate);
                trackProblems = List.of();
                track = candidate;
                completeWhateverTheNewTargetsAlreadyReach();
                Bukkit.getScheduler().runTaskAsynchronously(this, this::loadSeasonState);
                getLogger().info("the milestone track was reloaded: " + track.size()
                        + " milestones");
            }
        } catch (final ConfigException | RuntimeException exception) {
            getLogger().severe("the milestone track could not be reloaded, the running one is "
                    + "unchanged: " + exception.getMessage());
        }

        // The wording is reloaded in the same breath and reported separately, because the two fail
        // independently: a broken milestones.yml must not stop a corrected message from arriving,
        // and a typo'd override must not read as a track that failed to load.
        try {
            messages.reload();
            // The command inbox's own view of the shared bundle. Its unknown keys are deliberately
            // not reported: it holds one root, so a key this module declares would be named unknown.
            if (sharedMessages != null) {
                sharedMessages.reload();
            }
            reportUnknownOverrides();
            getLogger().info("the message bundles were reloaded");
        } catch (final RuntimeException exception) {
            getLogger().severe("the messages could not be reloaded, the running ones are "
                    + "unchanged: " + exception.getMessage());
        }

        return trackProblems;
    }

    /**
     * Names every override entry that overrode nothing.
     *
     * <p>An override for a key no bundle declares is stored and never looked up, so the failure is
     * a line that does not change and no error anywhere.
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
     * with a border that moved before it.
     */
    /**
     * What {@code /smp status} says, in the asker's language. Off the main thread: the phase is a
     * read of {@code season_phase}, and the effects only ever call this from their executor.
     */
    private SmpEffects.Status status(final java.util.Locale locale) {
        final String phase = eu.nordtal.s2.common.phase.PhaseDirectory.using(pool).currentPhase().name();
        final SeasonState.Active active = season.active();
        final java.util.Optional<String> milestone = active.key() == null ? java.util.Optional.empty()
                : java.util.Optional.of(messages.hasTranslation(locale, "smp.milestone." + active.key())
                        ? messages.get(locale, "smp.milestone." + active.key()) : active.key());
        return new SmpEffects.Status(phase, milestone, (int) Math.round(active.progress() * 100),
                online);
    }

    /**
     * How many people are on this server, sampled on the main thread once a second.
     *
     * <p>{@code status()} runs on the effects' executor, and Paper's player collection is unsafe to
     * touch from anywhere but the server thread. A number at most a second old is what a status line
     * needs.</p>
     */
    private volatile int online;

    /**
     * The database rows the file's definition needs: one per milestone and one per objective.
     * Idempotent, and run at enable and after every accepted reload - an objective appended to
     * {@code milestones.yml} mid-season has to exist as a row before anybody can hand anything in
     * against it.
     */
    /**
     * Writes one row per milestone and one per objective, <b>all of them or none</b>.
     *
     * <p>The transaction matters because {@code ensureObjective} updates the target on conflict -
     * it rewrites the arithmetic {@code ObjectiveEngine#credit} reads, which takes {@code target}
     * from the row rather than from the running track. Statement by statement, a failure in the
     * middle would leave some objectives on the candidate file's targets and the rest on the
     * running track's, while the log said the reload had been refused.
     *
     * <p>Idempotent, so the transaction costs one round trip rather than a decision.</p>
     */
    private void ensureRows(final MilestoneTrack definition) {
        jdbi.useTransaction(handle -> {
            final SmpDao transactional = handle.attach(SmpDao.class);
            for (final Milestone milestone : definition.milestones()) {
                transactional.ensureMilestone(milestone.key(), MilestoneState.LOCKED.name());
                for (final eu.nordtal.s2.smp.milestone.Objective objective : milestone.objectives()) {
                    transactional.ensureObjective(milestone.key(), objective.key(),
                            objective.type().name(), objective.target());
                }
            }
        });
    }

    private void loadSeasonState() {
        ensureRows(track);
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
     * it over; everything else social sits inside radius 10. Get it wrong and the season's first
     * milestone means nothing, which nobody would report as a bug.
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
     * <p>It takes the server down with it rather than only disabling itself: on a dedicated backend,
     * a plugin that disables itself while Paper carries on leaves a Minecraft server with no season
     * on it - up, healthy on every check outside the JVM, and wrong only once somebody joins.
     *
     * <p>{@link #startHeartbeat()} sits below every refusal so the container does report it, but an
     * unhealthy container is a red square and nothing else: Docker restarts nothing on health alone.
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
