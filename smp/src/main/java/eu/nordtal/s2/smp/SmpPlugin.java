package eu.nordtal.s2.smp;

import eu.nordtal.s2.commands.remote.CommandRequests;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.papercommon.plugin.NordtalPlugin;
import eu.nordtal.s2.settings.Check;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.BukkitSmpEffects;
import eu.nordtal.s2.smp.command.NavigateCommand;
import eu.nordtal.s2.smp.command.SmpCommand;
import eu.nordtal.s2.smp.config.Milestones;
import eu.nordtal.s2.smp.config.MilestonesSpec;
import eu.nordtal.s2.smp.config.PrestigeSpec;
import eu.nordtal.s2.smp.config.SmpSettings;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.SoundsSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneState;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.StoredProgress;
import eu.nordtal.s2.smp.milestone.TrackNames;
import eu.nordtal.s2.smp.milestone.TrackValidation;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.npc.SpawnNpc;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.prestige.PrestigeColours;
import eu.nordtal.s2.smp.progress.GateHolders;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;
import eu.nordtal.s2.smp.progress.StatisticPoller;
import eu.nordtal.s2.smp.region.Box;
import eu.nordtal.s2.smp.region.Boxes;
import eu.nordtal.s2.smp.region.ConfigBoxes;
import eu.nordtal.s2.smp.stage.BukkitCinematics;
import eu.nordtal.s2.smp.state.SeasonState;
import eu.nordtal.s2.smp.travel.BalloonDisplay;
import eu.nordtal.s2.smp.world.Datapacks;
import eu.nordtal.s2.smp.world.Worlds;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The season 2 SMP: Nordtal, the Nether and the End, with milestones, aura, prestige, duels, POIs and graves.
 *
 * A startup refusal stops the server rather than letting it run degraded and do damage nobody can undo.
 */
public final class SmpPlugin extends NordtalPlugin {

    private Setting<SmpSpec> config;
    private Setting<MilestonesSpec> milestoneSettings;

    /** Its own group so a reload can re-read it, unlike {@code config.yml}, which is bound once at enable. */
    private Setting<SoundsSpec> soundSettings;

    /** Swapped by a reload; every listener holds this one instance. */
    SmpSounds sounds;

    private Setting<PrestigeSpec> prestigeSettings;

    /** The name colours; volatile, since a reload replaces them and renders read them through a supplier. */
    volatile PrestigeColours prestigeColours;

    /** The crest ladder; volatile, since a reload re-derives it off the main thread. */
    volatile Prestige prestige;

    /**
     * Effects for Brigadier handlers, on the async scheduler; the inbox's own run inline to settle their request row.
     */
    BukkitSmpEffects chatEffects;

    Outbox outbox;

    @Nullable
    ScheduledExecutorService commandWaiter;

    SmpDao dao;
    CommandRequests requests;
    Announcer announcer;
    /** What the last reload refused the track for, or empty when it took it. */
    private volatile List<String> trackProblems = List.of();

    /** {@code :commands}' bundle as the inbox renders it, a second view of the same files. */
    @Nullable
    Messages sharedMessages;

    /**
     * The milestone track; volatile, since {@code reloadTrack} writes it async and suggestions read it per keystroke.
     */
    volatile MilestoneTrack track;

    Worlds worlds;
    private Boxes balloons;
    final SeasonState season = new SeasonState();
    Identities identities;

    @Nullable
    SmpHud hud;

    @Nullable
    Boards boards;

    final Navigation navigation = new Navigation();
    ObjectiveEngine engine;

    @Nullable
    StatisticPoller poller;

    GateHolders gates;
    Graves graves;

    @Nullable
    Duels duels;

    @Nullable
    SpawnNpc npc;
    /** Stopped at disable, while players are still here. */
    @Nullable
    BukkitCinematics cinematics;

    @Nullable
    BalloonDisplay balloonDisplay;

    private PlayerSurfaces surfaces;
    private PresenceListener presence;

    @Override
    protected String settingsPrefix() {
        return "NORDTAL_SMP";
    }

    @Override
    protected List<String> bundles() {
        return List.of("messages/commands", "messages/smp");
    }

    @Override
    protected void prepare() {
        config = setting("config", SmpSpec.class, SmpSettings::check);
        milestoneSettings = setting("milestones", MilestonesSpec.class, SmpSettings::checkMilestones);
        soundSettings = setting("sounds", SoundsSpec.class, Check.none());
        prestigeSettings = setting("prestige", PrestigeSpec.class, SmpSettings::checkPrestige);
        loadMilestoneTrack();
        loadFeedbackPalettes();
        worlds = bootstrapWorlds(config.get());
        balloons = ConfigBoxes.balloons(config.get());
        final String placement = checkNordtalBalloon(config.get(), balloons);
        if (placement != null) {
            throw fatal("smp is not starting: " + placement);
        }
    }

    @Override
    protected boolean refusesWithoutIdentity() {
        // A login smp cannot identify would lose that player's progress.
        return true;
    }

    @Override
    protected PaperUser.Chime chime() {
        return sounds::play;
    }

    @Override
    protected void enable() {
        final SmpSpec spec = config.get();
        final Boxes regions = ConfigBoxes.spawnRegions(spec);
        dao = jdbi().onDemand(SmpDao.class);
        identities = new Identities(identities(), dao);
        // Everything below this line touches the database, so it happens off the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(this, this::loadSeasonState);

        final SmpStart.HudAndAnnouncer ha = SmpStart.startHudAndAnnouncer(this);
        hud = ha.hud();
        requests = ha.requests();
        announcer = ha.announcer();

        final SmpStart.Surfaces wired = SmpStart.wireEffectsAndSurfaces(this, spec);
        boards = wired.boards();
        surfaces = wired.surfaces();
        final SmpStart.Presence inputs = SmpStart.wirePresenceInputs(this, spec, wired);
        cinematics = inputs.cinematics();
        presence = SmpStart.registerPresenceListeners(this, spec, wired, inputs);

        final SmpStart.Progress progress = SmpStart.wireProgressEngine(this, spec, wired.effects());
        engine = progress.engine();
        poller = progress.poller();
        gates = progress.gates();

        final SmpStart.Activities activities = SmpStart.wireActivities(this, spec, wired.effects());
        graves = activities.graves();
        duels = activities.duels();
        SmpStart.registerActivityListeners(this, spec, activities);
        npc = SmpStart.wireNpc(this, spec);
        balloonDisplay = SmpStart.restoreGravesAndRegisterWorld(this, balloons, regions, wired.effects());

        final SmpStart.CommandLayer layer = SmpStart.wireCommandLayer(this);
        chatEffects = layer.chatEffects();
        commandWaiter = layer.commandWaiter();
        outbox = layer.outbox();
        sharedMessages = layer.sharedMessages();
        registerCommands(sounds);
        // Before the surfaces, so the pass that starts the track over also draws it; any pass catches a missed switch.
        hub().on(Channel.PHASE, "the season reset", this::startTrackOverIfDue);
        // Surfaces are drawn far more often than their data changes, so they re-read only on its signal.
        hub().on(Channel.SMP, "the boards and HUD", this::refreshSurfaceData);
        layer.inbox().listen(hub(), this);
        getLogger()
                .info(track.size() + " milestones, " + regions.all().size() + " protected boxes, "
                        + balloons.all().size() + " balloons");
    }

    @Override
    protected void languageKnown(final Player player) {
        presence.languageKnown(player);
    }

    @Override
    protected void adminsChanged() {
        surfaces.refreshAll();
    }

    private void loadMilestoneTrack() {
        final Milestones.Result milestonesResult = Milestones.read(milestoneSettings.get());
        if (!milestonesResult.problems().isEmpty()) {
            milestonesResult.problems().forEach(problem -> getLogger().severe("milestones.yml: " + problem));
        }
        final MilestoneTrack loadedTrack = milestonesResult.track();
        if (loadedTrack == null) {
            throw fatal("smp is not starting: milestones.yml could not be parsed into a track at all - "
                    + "see the problem just logged.");
        }
        final List<TrackValidation.Problem> unknown = TrackNames.validate(loadedTrack, TrackNames.Server.running());
        if (!unknown.isEmpty()) {
            unknown.forEach(problem -> getLogger().severe("milestones.yml: " + problem));
            throw fatal("smp is not starting: milestones.yml names what this server does not have, see the"
                    + " problems just logged.");
        }
        track = loadedTrack;
    }

    private void loadFeedbackPalettes() {
        // A wrong sound key silences its own category rather than refusing the start.
        sounds = SmpSounds.of(soundSettings.get(), getLogger()::warning);
        prestigeColours = PrestigeColours.parse(
                SmpSettings.declaredPrestigeTiers(prestigeSettings.get()),
                prestigeSettings.get().admin(),
                getLogger()::warning);
        prestige = new Prestige(SmpSettings.declaredPrestigeHours(prestigeSettings.get()));
    }

    private Worlds bootstrapWorlds(final SmpSpec config) {
        // A world generated without them is vanilla terrain permanently, and Nordtal's terrain is never re-rolled.
        final Datapacks.Result packs = Datapacks.check(config.requiredDatapacks());
        if (!packs.ok()) {
            throw fatal("smp is not starting: " + packs.describe() + ". Datapacks are read once at server "
                    + "start, so installing them now would not change any terrain - put them in the "
                    + "level-name world's datapacks/ folder and restart.");
        }

        final Worlds candidate = new Worlds(config);
        final World nordtal = candidate.bootstrap().orElse(null);
        if (nordtal == null) {
            throw fatal("smp is not starting: the world '" + config.worldNordtal() + "' does not exist. "
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
    protected void disable() {
        // First: Paper disables plugins before saving players, so a spinning wheel pays out now.
        quietly("wheel.payOutInFlight", this::payOutSpinsInFlight);
        // A staging still running holds a potion effect on the player.
        final BukkitCinematics staging = cinematics;
        if (staging != null) {
            quietly("cinematics.stop", staging::stop);
        }
        final SpawnNpc figure = npc;
        if (figure != null) {
            quietly("npc.remove", figure::remove);
        }
        final BalloonDisplay display = balloonDisplay;
        if (display != null) {
            quietly("balloonDisplay.remove", display::remove);
        }
        final Duels running = duels;
        if (running != null) {
            quietly("duels.stop", running::stop);
        }
        if (graves != null) {
            quietly("graves.clearDisplays", graves::clearDisplays);
        }
        final StatisticPoller statistics = poller;
        if (statistics != null) {
            quietly("poller.stop", statistics::stop);
        }
        final SmpHud heads = hud;
        if (heads != null) {
            quietly("hud.stop", heads::stop);
        }
        final Boards drawn = boards;
        if (drawn != null) {
            quietly("boards.stop", drawn::stop);
        }
        final ScheduledExecutorService waiter = commandWaiter;
        if (waiter != null) {
            // Before the pool, since a wait in flight reads the request row through it.
            quietly("commandWaiter.shutdownNow", waiter::shutdownNow);
        }
    }

    void registerCommands(final SmpSounds sounds) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands = new NavigateCommand(
                    this, dao, navigation, identities, messages(), locales(), sounds, this::colours);
            // Not in {@code :commands}: both open an inventory or read the caller's position.
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());

            SmpCommand.build(
                            this,
                            messages(),
                            locales(),
                            identities,
                            sounds,
                            outbox,
                            chatEffects,
                            // A supplier, since {@code /smp reload} replaces the track.
                            () -> track,
                            season,
                            this::colours)
                    .forEach(node -> event.registrar().register(node));
        });
    }

    /** Starts the track over when the phase stamped a fresh start this server has not applied yet. */
    void startTrackOverIfDue() {
        if (dao.startOverIfDue() > 0) {
            getLogger().info("the season started over: every milestone is locked and every objective is empty");
        }
    }

    /** Reads the data every surface draws, on the signal hub's thread, so no render waits on the database. */
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
            Objects.requireNonNull(poller).setActiveMilestone(active);
            gates.setActiveMilestone(active);
            Objects.requireNonNull(boards).setLeaderboard(dao.topAura(10));
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

    /** Re-reads the settings and the bundles while players are online and returns the track's problems. */
    List<String> reloadTrack() {
        final List<String> _ = reload();
        return trackProblems;
    }

    @Override
    protected List<String> reloadOwn() {
        // Sounds go first, since they are iterated on live; each group fails on its own.
        final List<String> problems = new ArrayList<>();
        try {
            soundSettings.reload();
            sounds.reload(soundSettings.get());
        } catch (final SettingsException | RuntimeException failure) {
            problems.add("the sounds: " + failure.getMessage());
        }
        try {
            prestigeSettings.reload();
            prestigeColours = PrestigeColours.parse(
                    SmpSettings.declaredPrestigeTiers(prestigeSettings.get()),
                    prestigeSettings.get().admin(),
                    getLogger()::warning);
            prestige = new Prestige(SmpSettings.declaredPrestigeHours(prestigeSettings.get()));
        } catch (final SettingsException | RuntimeException failure) {
            problems.add("the prestige name colours: " + failure.getMessage());
        }
        reloadMilestoneTrack();
        final Messages shared = sharedMessages;
        if (shared != null) {
            shared.reload();
        }
        return problems;
    }

    private void reloadMilestoneTrack() {
        try {
            milestoneSettings.reload();
            final Milestones.Result reloaded = Milestones.read(milestoneSettings.get());
            final MilestoneTrack candidate = reloaded.track();

            final List<TrackValidation.Problem> problems;
            if (candidate == null) {
                // The file is structurally broken; there is nothing to compare.
                problems = reloaded.problems();
            } else {
                // A renamed key orphans progress, a moved target rewrites the ledger, an unknown name never counts.
                problems = new ArrayList<>(TrackValidation.validate(
                        candidate, new StoredProgress(dao.storedMilestones(), dao.storedObjectives())));
                problems.addAll(TrackNames.validate(candidate, TrackNames.Server.running()));
            }
            if (!problems.isEmpty() || candidate == null) {
                getLogger().severe("the milestone track was NOT reloaded, the running track is unchanged:");
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
        } catch (final SettingsException | RuntimeException exception) {
            getLogger()
                    .severe("the milestone track could not be reloaded, the running one is " + "unchanged: "
                            + exception.getMessage());
        }
    }

    /**
     * Writes one row per milestone and one per objective, in one transaction.
     *
     * Otherwise a failed reload could leave the targets {@code ObjectiveEngine#credit} reads half updated.
     */
    private void ensureRows(final MilestoneTrack definition) {
        jdbi().useTransaction(handle -> {
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
}
