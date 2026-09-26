package eu.nordtal.s2.smp;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.common.message.ToneColours;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.command.UpdateWatcher;
import eu.nordtal.s2.papercommon.stage.BukkitCinematics;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.BukkitSmpEffects;
import eu.nordtal.s2.smp.command.NavigateCommand;
import eu.nordtal.s2.smp.command.SmpCommand;
import eu.nordtal.s2.smp.config.ColoursSpec;
import eu.nordtal.s2.smp.config.Configs;
import eu.nordtal.s2.smp.config.DatabaseSpec;
import eu.nordtal.s2.smp.config.Milestones;
import eu.nordtal.s2.smp.config.MilestonesSpec;
import eu.nordtal.s2.smp.config.PrestigeSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.SoundsSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.milestone.MilestoneState;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.StoredProgress;
import eu.nordtal.s2.smp.milestone.TrackValidation;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.npc.SpawnNpc;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerComposition;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.prestige.PrestigeColours;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;
import eu.nordtal.s2.smp.progress.StatisticPoller;
import eu.nordtal.s2.smp.region.Box;
import eu.nordtal.s2.smp.region.Boxes;
import eu.nordtal.s2.smp.region.ConfigBoxes;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.travel.BalloonDisplay;
import eu.nordtal.s2.smp.world.Datapacks;
import eu.nordtal.s2.smp.world.Worlds;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The season 2 SMP: Nordtal, the Nether and the End, plus milestones, aura, prestige, duels, POIs and graves.
 * Wiring and startup refusals: each stops the plugin rather than letting it run degraded, since each produces
 * damage that cannot be undone.
 */
public final class SmpPlugin extends JavaPlugin {

    private ConfigHandle<SmpSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
    private ConfigHandle<MilestonesSpec> milestonesHandle;

    /**
     * Its own file, and its own handle, so that {@code /smp reload} can re-read it.
     *
     * {@code config.yml} deliberately cannot be reloaded: the plugin binds worlds, borders and coordinates once at
     * enable and would not notice them changing.
     */
    private ConfigHandle<SoundsSpec> soundsHandle;

    /**
     * Held so {@code /smp reload} can swap what it answers; every listener has this one instance.
     *
     * Package-private: {@link SmpStart} wires it into listener constructors it builds on this plugin's behalf.
     */
    SmpSounds sounds;

    /** Its own file, and its own handle, so that {@code /smp reload} can re-read it. */
    private ConfigHandle<ColoursSpec> coloursHandle;

    /**
     * The tone palette, replaced by {@code /smp reload}.
     *
     * <b>volatile</b> for the same reason {@link #track} is: a reload on another thread is visible here at once.
     */
    volatile ToneColours colours;

    /** Its own file, and its own handle, so that {@code /smp reload} can re-read it. */
    private ConfigHandle<PrestigeSpec> prestigeHandle;

    /**
     * The name colours, replaced by {@code /smp reload}.
     *
     * <b>volatile</b> for the same reason {@link #colours} is: {@link PlayerComposition} reads it through a supplier,
     * not a captured value, so a reload on another thread is visible to the very next render.
     */
    volatile PrestigeColours prestigeColours;

    /**
     * The crest ladder, re-derived on every {@code /smp reload}.
     *
     * Volatile for the same reason {@link #prestigeColours} is: reads run on the main thread while a reload runs
     * elsewhere, and a half-swapped table is not worth having.
     */
    volatile Prestige prestige;

    HikariDataSource pool;
    AdminWatch adminWatch;

    /** What a non-admin may type here, and what their client is told exists. */
    eu.nordtal.s2.papercommon.command.CommandFilter commandFilter;

    /**
     * The command layer: what this server runs itself, what it sends elsewhere, and what it is asked to run.
     *
     * Two {@code SmpEffects} exist since the executor differs: {@link #chatEffects} uses the plugin's async
     * scheduler for a Brigadier handler, the inbox's uses {@code Runnable::run} to settle its request row on return.
     */
    BukkitSmpEffects chatEffects;

    Outbox outbox;
    ScheduledExecutorService commandWaiter;
    SmpDao dao;
    Jdbi jdbi;
    Messages messages;
    eu.nordtal.s2.common.command.CommandRequests requests;
    eu.nordtal.s2.smp.announce.Announcer announcer;
    /**
     * What the last {@code /smp reload} refused the file for, or empty when it took it.
     *
     * Read by the reload command so the answer names the disagreement rather than only saying that something went
     * wrong.
     */
    private volatile List<String> trackProblems = List.of();

    /** {@code :commands}' bundle as the inbox renders it - a second view of the same files. */
    Messages sharedMessages;

    PlayerLocales locales;

    /**
     * The milestone track, replaced by {@code /smp reload}.
     *
     * <b>volatile</b>: {@code reloadTrack} writes it on Bukkit's async executor, and the suggestion supplier reads
     * it on the server thread once per keystroke. Package-private so {@link SmpStart} can pass it on as a supplier.
     */
    volatile MilestoneTrack track;

    Worlds worlds;
    final SeasonState season = new SeasonState();
    Identities identities;
    SmpHud hud;
    Boards boards;
    final Navigation navigation = new Navigation();
    ObjectiveEngine engine;
    StatisticPoller poller;
    Graves graves;
    Duels duels;
    SpawnNpc npc;
    /** The staging device - see BukkitCinematics. Stopped at disable, while players are still here. */
    BukkitCinematics cinematics;

    BalloonDisplay balloonDisplay;
    private org.bukkit.scheduler.BukkitTask heartbeat;

    /**
     * One try around the whole start.
     *
     * Otherwise a throw would escape {@code onEnable} and Paper would run on without this plugin; severe prevents that.
     *
     * The readiness marker makes that state visible but not safe: nothing outside this JVM acts on it, so stopping
     * the server is still ours to do. {@code RuntimeException} only, because {@code start()} already answers
     * {@code ConfigException} where it is thrown.
     */
    @Override
    public void onEnable() {
        // Loads the class every disable step needs, while the jar it lives in still exists; see Shutdown#warmUp.
        eu.nordtal.s2.common.health.Shutdown.warmUp();
        // {server.name} in every message: the plugin's name is the service's.
        eu.nordtal.s2.common.message.context.Contexts.server(getName());
        try {
            start();
        } catch (final Refusal refusal) {
            // Already logged, and the shutdown is already in motion - see severe(String).
        } catch (final RuntimeException failure) {
            severe("smp is not starting: " + failure.getMessage());
        }
    }

    /** Everything a start consists of. Throws rather than half-starting; see {@link #onEnable()}. */
    private void start() {
        loadConfigHandles();
        final SmpSpec config = configHandle.get();
        loadMilestoneTrack();
        loadFeedbackPalettes();
        worlds = bootstrapWorlds(config);

        final Boxes balloons = ConfigBoxes.balloons(config);
        final String placement = checkNordtalBalloon(config, balloons);
        if (placement != null) {
            throw severe("smp is not starting: " + placement);
        }

        final Boxes regions = ConfigBoxes.spawnRegions(config);
        wireEverything(config, databaseHandle.get(), balloons, regions);
        startHeartbeat();
        getLogger()
                .info("smp enabled - " + track.size() + " milestones, "
                        + regions.all().size() + " protected boxes, "
                        + balloons.all().size() + " balloons");
    }

    /**
     * Assigns every field {@link SmpStart} builds; see that class's doc comment for why assignment happens here.
     *
     * The sequence: database and messaging, the HUD and the surfaces, presence, progress, the activities, the NPC,
     * the world listeners, and finally the command layer.
     */
    private void wireEverything(
            final SmpSpec config, final DatabaseSpec database, final Boxes balloons, final Boxes regions) {
        final SmpStart.Database db = SmpStart.openDatabaseAndMessages(this, database);
        pool = db.pool();
        jdbi = db.jdbi();
        dao = db.dao();
        identities = db.identities();
        messages = db.messages();
        locales = db.locales();
        reportUnknownOverrides();
        // Everything below this line touches the database, so it happens off the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::loadSeasonState);

        final SmpStart.HudAndAnnouncer ha = SmpStart.startHudAndAnnouncer(this);
        hud = ha.hud();
        requests = ha.requests();
        announcer = ha.announcer();

        final SmpStart.Surfaces surfaces = SmpStart.wireEffectsAndSurfaces(this, config);
        boards = surfaces.boards();
        final AdminOperators operators = SmpStart.startSurfaceRefreshAndOperatorSweep(this);
        final SmpStart.Presence presence = SmpStart.wirePresenceInputs(this, config, surfaces);
        cinematics = presence.cinematics();
        SmpStart.registerPresenceListeners(this, config, surfaces, operators, presence);
        adminWatch = SmpStart.buildAdminWatch(this, operators, presence.admission(), surfaces.surfaces());

        final SmpStart.Progress progress = SmpStart.wireProgressEngine(this, config, surfaces.effects());
        engine = progress.engine();
        poller = progress.poller();

        final SmpStart.Activities activities = SmpStart.wireActivities(this, config, surfaces.effects());
        graves = activities.graves();
        duels = activities.duels();
        SmpStart.registerActivityListeners(this, config, activities);
        npc = SmpStart.wireNpc(this, config);
        balloonDisplay = SmpStart.restoreGravesAndRegisterWorld(this, balloons, regions, surfaces.effects());

        final SmpStart.CommandLayer layer = SmpStart.wireCommandLayer(this);
        chatEffects = layer.chatEffects();
        commandWaiter = layer.commandWaiter();
        outbox = layer.outbox();
        sharedMessages = layer.sharedMessages();
        registerCommands(sounds);
        commandFilter = SmpStart.wireCommandFilterAndStartWatch(this, config, database, layer.inbox());
    }

    private void loadConfigHandles() {
        try {
            configHandle = Configs.load(getDataFolder().toPath(), logger());
            databaseHandle = Configs.database(getDataFolder().toPath(), logger());
            milestonesHandle = Configs.milestones(getDataFolder().toPath(), logger());
            soundsHandle = Configs.sounds(getDataFolder().toPath(), logger());
            coloursHandle = Configs.colours(getDataFolder().toPath(), logger());
            prestigeHandle = Configs.prestige(getDataFolder().toPath(), logger());
        } catch (final ConfigException exception) {
            throw severe("smp is not starting because its configuration could not be read: " + exception.getMessage());
        }
    }

    private void loadMilestoneTrack() {
        final Milestones.Result milestonesResult = Milestones.read(milestonesHandle.get());
        if (!milestonesResult.problems().isEmpty()) {
            milestonesResult.problems().forEach(problem -> getLogger().severe("milestones.yml: " + problem));
        }
        final MilestoneTrack loadedTrack = milestonesResult.track();
        if (loadedTrack == null) {
            throw severe("smp is not starting: milestones.yml could not be parsed into a track at all - "
                    + "see the problem just logged.");
        }
        track = loadedTrack;
    }

    private void loadFeedbackPalettes() {
        // The sound vocabulary, read once; a wrong key silences its own category rather than joining the refusals.
        sounds = SmpSounds.of(soundsHandle.get(), getLogger()::warning);

        // The tone palette, read once here and re-read by reloadTrack; a bad hex value falls back to the default.
        colours = ToneColours.parse(Configs.declared(coloursHandle.get()), getLogger()::warning);

        // The prestige name palette, read once here and re-read by reloadTrack; a bad hex value falls back to default.
        prestigeColours = PrestigeColours.parse(
                Configs.declaredPrestigeTiers(prestigeHandle.get()),
                prestigeHandle.get().admin(),
                getLogger()::warning);
        prestige = new Prestige(Configs.declaredPrestigeHours(prestigeHandle.get()));
    }

    private Worlds bootstrapWorlds(final SmpSpec config) {
        // A world generated without them is vanilla terrain permanently, and Nordtal's terrain is never re-rolled.
        final Datapacks.Result packs = Datapacks.check(config.requiredDatapacks());
        if (!packs.ok()) {
            throw severe("smp is not starting: " + packs.describe() + ". Datapacks are read once at server "
                    + "start, so installing them now would not change any terrain - put them in the "
                    + "level-name world's datapacks/ folder and restart.");
        }

        final Worlds candidate = new Worlds(config);
        final World nordtal = candidate.bootstrap().orElse(null);
        if (nordtal == null) {
            throw severe("smp is not starting: the world '" + config.worldNordtal() + "' does not exist. "
                    + "It carries the built spawn, so an empty replacement would hide a broken "
                    + "deployment behind a world nobody recognises.");
        }

        // Not a refusal: a first join that cannot be placed just spawns where the server always would.
        if (Bukkit.getWorld(config.firstJoinSpawn().world()) == null) {
            getLogger()
                    .warning("first-join-spawn names the world '"
                            + config.firstJoinSpawn().world() + "', which does not exist on this server. "
                            + "First joins will not be moved anywhere. The build world is called '"
                            + config.worldNordtal() + "'.");
        }
        candidate.applyFixedBorders();
        return candidate;
    }

    /**
     * The container readiness marker - see {@link Readiness}, and note where this call sits.
     *
     * It is the <b>last</b> thing {@code start()} does: every refusal above returns before reaching it, so a marker
     * on disk means this plugin got all the way through. Written from Bukkit's async scheduler, because a repeating
     * async task is re-queued by the main-thread heartbeat, so a server frozen mid-tick goes stale rather than
     * staying green on an open port.
     */
    void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler().runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    /**
     * Hands over any prize whose animation is still running. <b>Main thread, at disable.</b>
     *
     * {@code WheelGui#finish} is a one-shot latch, so a wheel that has already paid is a no-op here, and one the player
     * closes a tick later cannot pay twice.
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
        // Stops the beat, so a server going down stops claiming to be up; the marker stays, since stale is the signal.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        // Before anything else that could throw: a spinning wheel pays out now, since Paper disables plugins first.
        quietly("wheel.payOutInFlight", this::payOutSpinsInFlight);
        // Before anything else that touches players: a staging still running holds a potion effect on the player.
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
        // Before the pool: the listener thread has its own connection, but a refresh in flight reads through the pool.
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
        eu.nordtal.s2.common.health.Shutdown.quietly(
                what, step, (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    void registerCommands(final SmpSounds sounds) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands =
                    new NavigateCommand(this, dao, navigation, identities, messages, locales, sounds, () -> colours);
            // Not folded into :commands: both open an inventory or read the caller's position, which Discord cannot do.
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());

            SmpCommand.build(
                            this,
                            messages,
                            locales,
                            identities,
                            sounds,
                            outbox,
                            chatEffects,
                            // steward-worker is a different container, reached only through a row and a notification.
                            new UpdateWatcher(this, UpdateDirectory.using(pool)),
                            // A supplier and not the field: /smp reload replaces it.
                            () -> track,
                            season,
                            () -> colours)
                    .forEach(node -> event.registrar().register(node));
        });
    }

    /**
     * The one place a surface's data is read. <b>Async</b>, on a timer.
     *
     * Both halves are drawn far more often than they change, so reading either at the point of use would put a database
     * round trip inside a render loop.
     */
    void refreshSurfaceData() {
        try {
            final java.util.Optional<String> active = dao.activeMilestoneKey();
            active.ifPresentOrElse(
                    key -> season.refreshActive(key, dao.objectivesOf(key)),
                    () -> season.refreshActive(null, java.util.List.of()));
            poller.setActiveMilestone(active);
            boards.setLeaderboard(dao.topAura(10));
        } catch (final RuntimeException exception) {
            // A briefly unreachable database must not kill the task; surfaces keep showing what they last knew.
            getLogger().warning("could not refresh the boards and HUD: " + exception);
        }
    }

    /**
     * Finishes every objective the reloaded targets have already been reached by. <b>Async.</b>
     *
     * Without this a lowered target only takes effect the next time somebody hands something in - and for an objective
     * that has become impossible there is no next time, which is why the target was lowered in the first place.
     *
     * {@code finishObjective} guards itself in SQL, so running this on every reload is safe and a reload that changed
     * nothing does nothing. {@code completedBy} is null: a target moved by hand has no player standing behind it.
     */
    private void completeWhateverTheNewTargetsAlreadyReach() {
        dao.activeMilestoneKey()
                .ifPresent(milestoneKey -> dao.objectivesOf(milestoneKey).stream()
                        .filter(row -> !row.completed())
                        .filter(row -> row.amount() >= row.target())
                        .forEach(row -> {
                            getLogger()
                                    .info("objective '" + row.key() + "' is already at " + row.amount()
                                            + " of its new target " + row.target() + " - completing it now");
                            engine.finishObjective(milestoneKey, row, null);
                        }));
    }

    /**
     * Re-reads {@code milestones.yml} while players are online.
     *
     * A target lowered below its collected progress completes the objective at once and pays it out. Only the track is
     * re-read, never the duel loadouts or the database password.
     */
    List<String> reloadTrack() {
        // Five files, five reports, five independent failures; sounds go first since that is the one iterated on live.
        reloadSounds();
        reloadColours();
        reloadPrestige();
        reloadMilestoneTrack();
        reloadMessages();
        return trackProblems;
    }

    private void reloadSounds() {
        try {
            soundsHandle.reload();
            sounds.reload(soundsHandle.get());
            getLogger().info("the sounds were reloaded");
        } catch (final ConfigException | RuntimeException exception) {
            getLogger()
                    .severe("the sounds could not be reloaded, the running ones are unchanged: "
                            + exception.getMessage());
        }
    }

    private void reloadColours() {
        try {
            coloursHandle.reload();
            colours = ToneColours.parse(Configs.declared(coloursHandle.get()), getLogger()::warning);
            getLogger().info("the tone colours were reloaded");
        } catch (final ConfigException | RuntimeException exception) {
            getLogger()
                    .severe("the tone colours could not be reloaded, the running ones are " + "unchanged: "
                            + exception.getMessage());
        }
    }

    private void reloadPrestige() {
        try {
            prestigeHandle.reload();
            prestigeColours = PrestigeColours.parse(
                    Configs.declaredPrestigeTiers(prestigeHandle.get()),
                    prestigeHandle.get().admin(),
                    getLogger()::warning);
            // The hours live beside the colours and reload with them, so a changed tier shows immediately.
            prestige = new Prestige(Configs.declaredPrestigeHours(prestigeHandle.get()));
            getLogger().info("the prestige name colours were reloaded");
        } catch (final ConfigException | RuntimeException exception) {
            getLogger()
                    .severe("the prestige name colours could not be reloaded, the running ones " + "are unchanged: "
                            + exception.getMessage());
        }
    }

    private void reloadMilestoneTrack() {
        try {
            milestonesHandle.reload();
            final Milestones.Result reloaded = Milestones.read(milestonesHandle.get());
            final MilestoneTrack candidate = reloaded.track();

            final List<TrackValidation.Problem> problems;
            if (candidate == null) {
                // The file itself is not a track, a structural problem TrackShape already caught; nothing to compare.
                problems = reloaded.problems();
            } else {
                // Validated against the rows: a renamed key orphans progress and a moved target rewrites the ledger.
                problems = TrackValidation.validate(
                        candidate, new StoredProgress(dao.storedMilestones(), dao.storedObjectives()));
            }
            // candidate == null only fires with a non-empty problems list, which Milestones.read always adds.
            if (!problems.isEmpty() || candidate == null) {
                getLogger()
                        .severe("the milestone track was NOT reloaded - the file disagrees with"
                                + " progress this season has already recorded, and the running track is"
                                + " unchanged:");
                problems.forEach(problem -> getLogger().severe("  " + problem));
                trackProblems =
                        problems.stream().map(TrackValidation.Problem::toString).toList();
                // No early return: the three reloads fail independently; a broken track must not block a fixed message.
            } else {
                // Rows first, track second: if ensureRows throws, the catch below is right only while track is unset.
                ensureRows(candidate);
                trackProblems = List.of();
                track = candidate;
                completeWhateverTheNewTargetsAlreadyReach();
                Bukkit.getScheduler().runTaskAsynchronously(this, this::loadSeasonState);
                getLogger().info("the milestone track was reloaded: " + track.size() + " milestones");
            }
        } catch (final ConfigException | RuntimeException exception) {
            getLogger()
                    .severe("the milestone track could not be reloaded, the running one is " + "unchanged: "
                            + exception.getMessage());
        }
    }

    private void reloadMessages() {
        // Reloaded and reported separately from the track, since a broken milestones.yml must not block this.
        try {
            messages.reload();
            // The inbox's own view of the shared bundle; its unknown keys go unreported since it holds only one root.
            if (sharedMessages != null) {
                sharedMessages.reload();
            }
            reportUnknownOverrides();
            getLogger().info("the message bundles were reloaded");
        } catch (final RuntimeException exception) {
            getLogger()
                    .severe("the messages could not be reloaded, the running ones are " + "unchanged: "
                            + exception.getMessage());
        }
    }

    /**
     * Names every override entry that overrode nothing.
     *
     * An override for a key no bundle declares is stored and never looked up, so the failure is a line that does not
     * change and no error anywhere.
     */
    void reportUnknownOverrides() {
        messages.unknownOverrideKeys()
                .forEach(key -> getLogger()
                        .warning("the message override names " + key + ", which no bundle declares - it is stored"
                                + " and never used; check the spelling"));
    }

    /**
     * What {@code /smp status} says, in the asker's language.
     *
     * Off the main thread: the phase is a read of {@code season_phase}, and the effects only call this from their
     * executor.
     */
    eu.nordtal.s2.smp.command.Standing.Status status(final java.util.Locale locale) {
        final String phase = eu.nordtal.s2.common.phase.PhaseDirectory.using(pool)
                .currentPhase()
                .name();
        final SeasonState.Active active = season.active();
        final java.util.Optional<String> milestone = active.key() == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(MilestoneNames.of(messages, locale, active.key()));
        return new eu.nordtal.s2.smp.command.Standing.Status(
                phase, !active.unread(), milestone, (int) Math.round(active.progress() * 100), online);
    }

    /**
     * How many people are on this server, sampled on the main thread once a second.
     *
     * {@code status()} runs on the effects' executor, and Paper's player collection is unsafe off the server thread.
     * A number at most a second old is what a status line needs.
     */
    volatile int online;

    /**
     * Writes one row per milestone and one per objective, <b>all of them or none</b>.
     *
     * Run at enable and after every accepted reload - an objective appended to {@code milestones.yml} mid-season has to
     * exist as a row before anybody can hand anything in against it.
     *
     * The transaction matters because {@code ensureObjective} updates the target on conflict - it rewrites the
     * arithmetic {@code ObjectiveEngine#credit} reads, which takes {@code target} from the row rather than from the
     * running track. A failure in the middle would otherwise leave some objectives on the candidate's targets and
     * the rest on the running track's, while the log said the reload had been refused.
     *
     * Idempotent, so the transaction costs one round trip rather than a decision.
     */
    private void ensureRows(final MilestoneTrack definition) {
        jdbi.useTransaction(handle -> {
            final SmpDao transactional = handle.attach(SmpDao.class);
            for (final Milestone milestone : definition.milestones()) {
                transactional.ensureMilestone(milestone.key(), MilestoneState.LOCKED.name());
                for (final eu.nordtal.s2.smp.milestone.Objective objective : milestone.objectives()) {
                    transactional.ensureObjective(
                            milestone.key(), objective.key(), objective.type().name(), objective.target());
                }
            }
        });
    }

    /**
     * Reads the track's progress and puts Nordtal's border where the completed milestones say.
     *
     * Runs async, then hops back for the border. Not animated: this is a restart catching up with a border that moved
     * before it.
     */
    void loadSeasonState() {
        ensureRows(track);
        final List<String> completed = dao.completedMilestoneKeys();
        season.refresh(completed, track);

        Bukkit.getScheduler().runTask(this, () -> {
            final int diameter = season.borderDiameter();
            if (diameter > 0) {
                worlds.expandNordtal(diameter, false);
            }
            getLogger()
                    .info("season state: " + completed.size() + " milestones complete, unlocks "
                            + season.unlocked() + ", Nordtal's border "
                            + (diameter > 0 ? String.valueOf(diameter) : "untouched"));
        });
    }

    /**
     * The one geometric rule the spawn build has to obey.
     *
     * Nordtal's balloon must stand outside radius 10 and inside radius 21.5 of the border centre. That is what
     * makes the opening border of 20 withhold travel and the first expansion to 43 hand it over; everything else
     * social sits inside radius 10.
     *
     * @return null when the placement is fine, or the complaint to refuse the start with
     */
    private @Nullable String checkNordtalBalloon(final SmpSpec config, final Boxes balloons) {
        final List<Box> inNordtal = balloons.in(config.worldNordtal());
        if (inNordtal.isEmpty()) {
            return "no balloon is configured in '" + config.worldNordtal() + "', so nobody could " + "ever leave it.";
        }
        for (final Box box : inNordtal) {
            final double distance = box.horizontalDistanceFrom(config.borderCentreX(), config.borderCentreZ());
            if (distance <= 10.0 || distance >= 21.5) {
                return String.format(
                        Locale.ROOT,
                        "Nordtal's balloon sits at radius %.1f of the border centre %d/%d. It has to "
                                + "be outside 10 and inside 21.5, because that is what makes border "
                                + "20 withhold travel and the opening expansion to 43 hand it "
                                + "over.",
                        distance,
                        config.borderCentreX(),
                        config.borderCentreZ());
            }
        }
        return null;
    }

    /**
     * The plugin cannot run, so neither can this server.
     *
     * It takes the server down with it rather than only disabling itself: a plugin that disables itself while Paper
     * carries on leaves a Minecraft server with no season on it - up and healthy on every check outside the JVM,
     * and wrong only once somebody joins.
     *
     * {@link #startHeartbeat()} sits below every refusal so the container does report it, but an unhealthy container is
     * a red square and nothing else: Docker restarts nothing on health alone.
     *
     * {@code disablePlugin} first and then {@code shutdown}: the disable runs whatever cleanup {@code onDisable} does,
     * and if the shutdown were ever ignored the plugin is still off rather than half-enabled.
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

    Logger logger() {
        return LoggerFactory.getLogger(getClass());
    }
}
