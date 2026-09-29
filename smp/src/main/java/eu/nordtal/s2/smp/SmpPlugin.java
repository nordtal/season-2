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
 * The season 2 SMP: Nordtal, the Nether and the End, with milestones, aura, prestige, duels, POIs and graves.
 *
 * A startup refusal stops the server rather than letting it run degraded and do damage nobody can undo.
 */
public final class SmpPlugin extends JavaPlugin {

    private ConfigHandle<SmpSpec> configHandle;
    private ConfigHandle<DatabaseSpec> databaseHandle;
    private ConfigHandle<MilestonesSpec> milestonesHandle;

    /**
     * Its own handle so {@code /smp reload} can re-read it, unlike {@code config.yml}, which is bound once at enable.
     */
    private ConfigHandle<SoundsSpec> soundsHandle;

    /** Swapped by {@code /smp reload}; every listener holds this one instance. */
    SmpSounds sounds;

    private ConfigHandle<ColoursSpec> coloursHandle;

    /** The tone palette; volatile, since {@code /smp reload} replaces it on another thread. */
    volatile ToneColours colours;

    private ConfigHandle<PrestigeSpec> prestigeHandle;

    /** The name colours; volatile, since {@code /smp reload} replaces them and renders read them through a supplier. */
    volatile PrestigeColours prestigeColours;

    /** The crest ladder; volatile, since {@code /smp reload} re-derives it off the main thread. */
    volatile Prestige prestige;

    HikariDataSource pool;
    AdminWatch adminWatch;

    /** What a non-admin may type here, and what their client is told exists. */
    eu.nordtal.s2.papercommon.command.CommandFilter commandFilter;

    /**
     * Effects for Brigadier handlers, on the async scheduler; the inbox's own run inline to settle their request row.
     */
    BukkitSmpEffects chatEffects;

    Outbox outbox;
    ScheduledExecutorService commandWaiter;
    SmpDao dao;
    Jdbi jdbi;
    Messages messages;
    eu.nordtal.s2.common.command.CommandRequests requests;
    eu.nordtal.s2.smp.announce.Announcer announcer;
    /** What the last {@code /smp reload} refused the file for, or empty when it took it. */
    private volatile List<String> trackProblems = List.of();

    /** {@code :commands}' bundle as the inbox renders it, a second view of the same files. */
    Messages sharedMessages;

    PlayerLocales locales;

    /**
     * The milestone track; volatile, since {@code reloadTrack} writes it async and suggestions read it per keystroke.
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
    /** Stopped at disable, while players are still here. */
    BukkitCinematics cinematics;

    BalloonDisplay balloonDisplay;
    private org.bukkit.scheduler.BukkitTask heartbeat;

    /**
     * Runs {@link #start()} and stops the server if it throws, rather than letting Paper run on without this plugin.
     */
    @Override
    public void onEnable() {
        // Loads what every disable step needs while the jar still exists.
        eu.nordtal.s2.common.health.Shutdown.warmUp();
        eu.nordtal.s2.common.message.context.Contexts.server(getName());
        try {
            start();
        } catch (final Refusal refusal) {
            // Already logged, and the shutdown is already in motion.
        } catch (final RuntimeException failure) {
            severe("smp is not starting: " + failure.getMessage());
        }
    }

    /** Everything a start consists of; throws rather than half-starting. */
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

    /** Assigns every field {@link SmpStart} builds, in dependency order, ending with the command layer. */
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
        // A wrong sound key silences its own category rather than refusing the start.
        sounds = SmpSounds.of(soundsHandle.get(), getLogger()::warning);

        // A bad hex value falls back to the default.
        colours = ToneColours.parse(Configs.declared(coloursHandle.get()), getLogger()::warning);

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
     * Starts the container readiness heartbeat, the last step of {@code start()}, so a marker means every check passed.
     *
     * Async, because an async repeating task is re-queued by the main-thread tick and so goes stale on a freeze.
     */
    void startHeartbeat() {
        final Readiness readiness = Readiness.onDefaultPath(getLogger()::warning);
        final long ticks = Readiness.BEAT.toSeconds() * 20L;
        heartbeat = getServer().getScheduler().runTaskTimerAsynchronously(this, readiness::refresh, 0L, ticks);
    }

    /** Hands over any prize whose animation is still running, on the main thread at disable. */
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
        // The marker stays, since a stale one is the signal.
        if (heartbeat != null) {
            quietly("heartbeat.cancel", heartbeat::cancel);
        }
        // First: Paper disables plugins before saving players, so a spinning wheel pays out now.
        quietly("wheel.payOutInFlight", this::payOutSpinsInFlight);
        // A staging still running holds a potion effect on the player.
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
        // Before the pool, since a refresh in flight reads through it.
        if (adminWatch != null) {
            quietly("adminWatch.close", adminWatch::close);
        }
        if (commandWaiter != null) {
            // Before the pool, since a wait in flight reads the request row through it.
            quietly("commandWaiter.shutdownNow", commandWaiter::shutdownNow);
        }
        if (pool != null) {
            quietly("pool.close", pool::close);
        }
        getLogger().info("smp disabled");
    }

    /** One disable step, isolated from the next. */
    private void quietly(final String what, final Runnable step) {
        eu.nordtal.s2.common.health.Shutdown.quietly(
                what, step, (message, failure) -> getLogger().log(java.util.logging.Level.WARNING, message, failure));
    }

    void registerCommands(final SmpSounds sounds) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands =
                    new NavigateCommand(this, dao, navigation, identities, messages, locales, sounds, () -> colours);
            // Not in {@code :commands}: both open an inventory or read the caller's position.
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
                            new UpdateWatcher(this, UpdateDirectory.using(pool)),
                            // A supplier, since {@code /smp reload} replaces the track.
                            () -> track,
                            season,
                            () -> colours)
                    .forEach(node -> event.registrar().register(node));
        });
    }

    /** Reads the data every surface draws, async on a timer, so no render waits on the database. */
    void refreshSurfaceData() {
        try {
            java.util.Optional<String> active = dao.activeMilestoneKey();
            final List<String> completed = dao.completedMilestoneKeys();
            if (!completed.equals(season.completedKeys())
                    || (active.isEmpty() && track.next(completed).isPresent())) {
                // A phase switch started the track over, or nothing has started it yet.
                loadSeasonState();
                active = dao.activeMilestoneKey();
            }
            active.ifPresentOrElse(
                    key -> season.refreshActive(key, dao.objectivesOf(key)),
                    () -> season.refreshActive(null, java.util.List.of()));
            poller.setActiveMilestone(active);
            boards.setLeaderboard(dao.topAura(10));
        } catch (final RuntimeException exception) {
            // Surfaces keep showing what they last knew.
            getLogger().warning("could not refresh the boards and HUD: " + exception);
        }
    }

    /**
     * Finishes, async, every objective whose reloaded target is already reached.
     *
     * Idempotent in SQL, so a lowered target completes at once and a reload that changed nothing does nothing.
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

    /** Re-reads the configuration files while players are online and returns the track's problems. */
    List<String> reloadTrack() {
        // Five files, five independent failures; sounds go first, since they are iterated on live.
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
                // The file is structurally broken; there is nothing to compare.
                problems = reloaded.problems();
            } else {
                // A renamed key orphans progress and a moved target rewrites the ledger.
                problems = TrackValidation.validate(
                        candidate, new StoredProgress(dao.storedMilestones(), dao.storedObjectives()));
            }
            if (!problems.isEmpty() || candidate == null) {
                getLogger()
                        .severe("the milestone track was NOT reloaded - the file disagrees with"
                                + " progress this season has already recorded, and the running track is"
                                + " unchanged:");
                problems.forEach(problem -> getLogger().severe("  " + problem));
                trackProblems =
                        problems.stream().map(TrackValidation.Problem::toString).toList();
                // The reloads fail independently, so a broken track must not block a fixed message.
            } else {
                // Rows first: if {@code ensureRows} throws, the track stays unset.
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
        // Separate from the track, so a broken {@code milestones.yml} does not block this.
        try {
            messages.reload();
            // Unknown keys go unreported, since this view holds only one root.
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

    /** Logs every override entry that overrode nothing, which is otherwise a silent no-op. */
    void reportUnknownOverrides() {
        messages.unknownOverrideKeys()
                .forEach(key -> getLogger()
                        .warning("the message override names " + key + ", which no bundle declares - it is stored"
                                + " and never used; check the spelling"));
    }

    /**
     * What {@code /smp status} says, in the asker's language.
     *
     * Off the main thread: it reads {@code season_phase}.
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

    /** The player count, sampled once a second on the main thread for {@code status()}, which runs elsewhere. */
    volatile int online;

    /**
     * Writes one row per milestone and one per objective, in one transaction.
     *
     * Otherwise a failed reload could leave the targets {@code ObjectiveEngine#credit} reads half updated.
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
     * Reads the track's progress and puts Nordtal's border where the completed milestones say, without animating it.
     */
    void loadSeasonState() {
        final MilestoneTrack now = track;
        ensureRows(now);
        final List<String> completed = dao.completedMilestoneKeys();
        // A reset after the read changes the count, so the activation does nothing and the next tick decides.
        if (dao.activeMilestoneKey().isEmpty()) {
            now.next(completed).ifPresent(next -> {
                if (dao.activateAfter(next.key(), completed.size()) > 0) {
                    getLogger().info("milestone " + next.key() + " is now active");
                }
            });
        }
        season.refresh(completed, now);

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
     * Returns why to refuse the start, or null when Nordtal's balloon sits between radius 10 and 21.5 of the centre.
     *
     * So the opening border of 20 withholds travel and the first expansion to 43 hands it over.
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
     * Logs the reason and shuts the server down, since a running server without its season looks healthy.
     *
     * {@code disablePlugin} runs first, so an ignored shutdown still leaves the plugin off rather than half-enabled.
     */
    private Refusal severe(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
        return new Refusal();
    }

    /** Thrown by {@link #severe(String)}, so NullAway sees that nothing after {@code throw severe(...)} runs. */
    private static final class Refusal extends RuntimeException {
        private Refusal() {
            super(null, null, false, false);
        }
    }

    Logger logger() {
        return LoggerFactory.getLogger(getClass());
    }
}
