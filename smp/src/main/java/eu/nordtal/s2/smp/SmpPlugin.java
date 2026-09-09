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

    /** What a non-admin may type here, and what their client is told exists. */
    private eu.nordtal.s2.papercommon.command.CommandFilter commandFilter;

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
    private Jdbi jdbi;
    private Messages messages;
    private eu.nordtal.s2.common.command.CommandRequests requests;
    private eu.nordtal.s2.smp.announce.Announcer announcer;
    /**
     * What the last {@code /smp reload} refused the file for, or empty when it took it.
     *
     * <p>Read by the reload command so the answer names the disagreement rather than only saying
     * that something went wrong - an operator editing {@code milestones.yml} mid-season is the one
     * person who can act on "you renamed a key that has rows against it".</p>
     */
    private volatile List<String> trackProblems = List.of();

    /** {@code :commands}' bundle as the inbox renders it - a second view of the same files. */
    private Messages sharedMessages;
    private PlayerLocales locales;

    /**

     * The milestone track, replaced by {@code /smp reload}.

     *

     * <p><b>volatile</b>, because the write and the reads are on different threads:

     * {@code reloadTrack} runs on Bukkit's async executor behind {@code /smp reload}, and the

     * suggestion supplier handed to {@code SmpCommand.build} reads it on the server thread,

     * once per keystroke. Without it the supplier may go on seeing the old instance for no

     * bounded length of time, which looks exactly like the captured-instance bug this supplier

     * exists to fix.</p>

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
    private BalloonDisplay balloonDisplay;
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
        jdbi = Jdbi.create(pool)
                .installPlugin(new SqlObjectPlugin())
                .installPlugin(new PostgresPlugin());
        dao = jdbi.onDemand(SmpDao.class);
        identities = new Identities(dao);

        // Three roots, most general first: :paper-common's five system lines, then :commands'
        // shared bundle, then this module's own. What a shared mechanism says has to say the same
        // thing on every surface. Later roots win, so this module's own keys beat both - which is
        // the mechanism for rewording a shared line here, and not a way of adding one.
        messages = Messages.load(getClass().getClassLoader(),
                java.util.List.of("messages/paper-common", "messages/commands", "messages/smp"),
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

        // The SMP's line into Discord: one command_request row per language, fire and forget.
        // Built before the two things that have a moment to announce, on the same request table
        // the outbox below uses.
        requests = eu.nordtal.s2.common.command.CommandRequests.borrowing(pool);
        announcer = new eu.nordtal.s2.smp.announce.Announcer(requests, messages,
                BukkitSmpEffects.async(this),
                (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));

        farmReset = new FarmWorldReset(this, config, worlds, swap, pregen, messages, locales,
                dao, navigation, sounds, hud, announcer);
        farmReset.start();

        // The network's backup clock, and it is here for a reason that is not about the SMP: the
        // updater must not schedule its own work (docs/updater.md - `serve` is not a scheduler),
        // and this is the one process that already runs a daily clock. It writes an update_request
        // row and nothing else; the updater does the stopping, the snapshot and the starting.
        nightlyBackup = new eu.nordtal.s2.smp.backup.NightlyBackup(this,
                UpdateDirectory.using(pool), BukkitSmpEffects.async(this), config.backupTime());
        nightlyBackup.start();

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
        // The one main-thread read of the player collection, for /smp status - see the field.
        Bukkit.getScheduler().runTaskTimer(this, () -> online = Bukkit.getOnlinePlayers().size(),
                20L, 20L);

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
        // The composition is this server's half of the shared lines: flag, name and the prestige
        // crest a season earns. Everything around it - the five keys, the icons, the per-reader
        // language - is :paper-common's and is the same on the hunger games.
        final SystemLines systemLines = new SystemLines(
                player -> composition.chatPrefix(player.getName(),
                        identities.of(player.getUniqueId())),
                messages, locales);
        getServer().getPluginManager().registerEvents(
                new PresenceListener(this, identities, surfaces, locales, operators, systemLines),
                this);
        getServer().getPluginManager().registerEvents(systemLines, this);
        getServer().getPluginManager().registerEvents(
                new NavigateListener(this, dao, navigation, identities, locales, sounds), this);
        // The start event's winner is paid on their FIRST join here, and never by hunger-games -
        // see HeadStart for why the dependency points this way round.
        getServer().getPluginManager().registerEvents(
                new HeadStart(this, dao, identities, surfaces, config, messages, locales, sounds),
                this);

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
                new NpcListener(this, dao, npc, () -> track, engine, identities, messages, locales,
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
                new BalloonListener(balloons, worlds, season, () -> track, messages, locales, sounds,
                        effects), this);
        // The balloon a player sees, as opposed to the box they step into: one item display per
        // configured box, wearing the pack's model. Until 2026-09-05 the pack carried the model
        // and nothing placed it, so the balloon was a volume of air that opened a menu.
        balloonDisplay = new BalloonDisplay(this, balloons);
        balloonDisplay.spawn();
        getServer().getPluginManager().registerEvents(
                new PortalGate(this, worlds, season, messages, locales, sounds), this);

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

        // Built here rather than inside the inbox so that /smp reload can move it: it is a second
        // view of the same message files, and one that never reloaded would answer a Discord admin
        // with the wording this process started with.
        sharedMessages = PaperCommandInbox.sharedBundle(this);
        final PaperCommandInbox inbox =
                new PaperCommandInbox(this, Target.SMP, requests, access, sharedMessages);
        // Inline, on purpose - see the field comment.
        final SmpEffects inboxEffects = new BukkitSmpEffects(this, Runnable::run, jdbi, dao, engine,
                farmReset, identities, access, this::reloadTrack, this::status);
        SmpCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.start(this);

        registerCommands(sounds);

        // The command allowlist. The proxy refuses a command before it reaches this server, which
        // is the enforcement; this is the half the proxy cannot do - what this server tells a
        // client exists at all. Same poll rhythm as the admin roster, and its notification rides
        // the same connection. See CommandFilter, which fails OPEN and says so if no list has been
        // published yet.
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
        // Stops the beat, so a server that is going down stops claiming to be up. The marker is
        // deliberately not deleted: going stale is the signal, and it costs nothing here.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        // Before anything else that could throw, and long before the pool: a wheel still spinning
        // when the server goes down pays out now or never.
        //
        // Paper disables plugins BEFORE it saves and disconnects players - measured on a real 26.2
        // shutdown on 2026-09-06, `Disabling smp` and the pool's `Shutdown completed` both come
        // several lines above `annicx lost connection`. So the InventoryCloseEvent that normally
        // finishes a spin arrives when no listener is registered any more, and the animation's own
        // chain simply stops at its next tick: the spin was already spent in SQL, and the player
        // got nothing (finding 136). Here they are still online, so this hands over the prize
        // itself - and `Saving players`, three lines later, is what writes it to disk.
        quietly("wheel.payOutInFlight", this::payOutSpinsInFlight);
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
        // Its own guard rather than farmReset's: the two are built one line apart, and a throw in
        // between would leave this null while farmReset is not - which is a NullPointerException
        // inside the shutdown that was already dealing with a broken start.
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
            // Not folded into :commands, deliberately: /navigate opens an inventory and /poi add
            // reads the caller's position. Both are commands about being somewhere, and a Discord
            // half of either would be a different command wearing the same name.
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());

            SmpCommand.build(this, messages, locales, identities, sounds, outbox, chatEffects,
                            // Over the pool this plugin already owns. The updater is a different
                            // container and this is how it is reached: a row and a notification,
                            // never a call.
                            new UpdateWatcher(this, UpdateDirectory.using(pool)),
                            // A supplier and not the field: /smp reload replaces it.
                            () -> track, season)
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
    /**
     * Finishes every objective the reloaded targets have already been reached by. <b>Async.</b>
     *
     * <p>This is the second half of the one thing {@code milestones.yml} is a reloadable file
     * <em>for</em>. Its own header promises it in as many words: lowering a target is "the finest
     * of the three escape hatches for an objective that turns out to be impossible, and if the
     * collected progress is already at or above the new target the objective completes at once and
     * pays its FULL pot". {@code TrackValidation} permits the lowering and has a test for permitting
     * it; {@code ObjectiveEngine#payOut} names the case in its javadoc. Nothing did it.
     *
     * <p>What the gap cost is exactly the case the hatch exists for. Completion is decided inside
     * {@code credit}, when progress is <em>added</em> - so a lowered target took effect the next
     * time somebody handed something in. For an objective that has become impossible there is no
     * next time, which is why the target was lowered. Measured on the local SMP, 2026-09-06:
     * {@code diamonds} at 20 with its target moved to 10, reload reported success, and the row sat
     * there unfinished with no aura paid (finding 129).
     *
     * <p>{@code finishObjective} guards itself in SQL - {@code completeObjective} returns zero for a
     * row somebody else has already closed - so running this on every reload is safe, and running
     * it on a reload that changed nothing does nothing. {@code completedBy} is null: a target moved
     * by hand has no player standing behind it, which is the same shape the admin escape hatch
     * already passes.
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
            final MilestoneTrack candidate = Milestones.read(milestonesHandle.get()).track();

            // "May this file replace the running one?" - asked against the rows, which is the only
            // place the answer lives. A renamed milestone key orphans everything recorded against
            // it, an objective that changes type carries progress that means something else now,
            // and a completed objective whose target moved rewrites arithmetic that is already in
            // the aura ledger. None of that is visible from the file.
            //
            // TrackValidation has answered this since the module was built and NOTHING CALLED IT:
            // twelve tests, two javadoc references, and no caller. So a reload that removed the
            // active milestone was applied, reported success, and stopped progression with nothing
            // anywhere saying why. Found through a review finding about something else, 2026-09-05;
            // the owner chose to refuse rather than warn on the same day.
            final List<TrackValidation.Problem> problems = TrackValidation.validate(candidate,
                    new StoredProgress(dao.storedMilestones(), dao.storedObjectives()));
            if (!problems.isEmpty()) {
                getLogger().severe("the milestone track was NOT reloaded - the file disagrees with"
                        + " progress this season has already recorded, and the running track is"
                        + " unchanged:");
                problems.forEach(problem -> getLogger().severe("  " + problem));
                trackProblems = problems.stream().map(TrackValidation.Problem::toString).toList();
                // No early return: the wording below is re-read regardless, for the reason the
                // comment there gives - the three fail independently, and a milestones.yml somebody
                // is still fixing must not hold back a corrected message.
            } else {
                // The rows first, the track second, deliberately. ensureRows writes one row per
                // milestone and one per objective; if it throws halfway, the catch below reports
                // that the running track is unchanged - which was a lie while the assignment came
                // first, because every command was already reading a definition whose objective
                // rows were missing or half written (finding 103).
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
            // The command inbox's own view of the shared bundle, in the same breath. Its unknown
            // keys are deliberately not reported: it holds one root, so a key this module declares
            // would be named as unknown by it and is not.
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
     * <p>{@code status()} runs on the effects' executor - an async task for the chat surface, the
     * request thread for the inbox - and Paper's player collection is documented as unsafe to touch
     * from anywhere but the server thread. A number that is at most a second old is what a status
     * line needs; reaching across for a live one is what it does not (finding 104).</p>
     */
    private volatile int online;

    /**
     * The database rows the file's definition needs: one per milestone and one per objective.
     * Idempotent, and run at enable and after every accepted reload - an objective appended to
     * {@code milestones.yml} mid-season has to exist as a row before anybody can hand anything in
     * against it. The objective half was missing until 2026-09-06 (finding 99).
     */
    /**
     * Writes one row per milestone and one per objective, <b>all of them or none</b>.
     *
     * <h2>Why the transaction</h2>
     * {@code ensureObjective} updates the target on conflict, because lowering one is the first
     * escape hatch for an objective that has become impossible. So this is not only an insert of
     * rows nothing reads yet: it rewrites the arithmetic {@code ObjectiveEngine#credit} reads,
     * which takes {@code target} from the database row rather than from the running track.
     *
     * <p>Statement by statement, a failure in the middle - a connection that has stopped answering
     * inside {@code query-timeout-seconds} is the ordinary way - left some objectives carrying the
     * candidate file's targets and the rest carrying the running track's, with {@code track} itself
     * unchanged and the log line saying the reload had been refused. Progress credited after that
     * would complete an objective against a number from a file that was never applied. One
     * transaction makes the reported outcome and the database agree (CodeRabbit, PR #8).
     *
     * <p>Called at enable and on every {@code /smp reload}; it is idempotent, so the cost of the
     * transaction is one round trip rather than a decision.</p>
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
