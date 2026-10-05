package eu.nordtal.s2.smp;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import eu.nordtal.displaytags.DisplayTags;
import eu.nordtal.displaytags.config.spec.NameTagConfigurationSpec;
import eu.nordtal.s2.database.inbox.SmpRequest;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.messagerendering.Names;
import eu.nordtal.s2.papercommon.command.Answer;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.papercommon.plugin.NordtalPlugin;
import eu.nordtal.s2.papercommon.sound.SoundsSpec;
import eu.nordtal.s2.papercommon.time.PaperScheduler;
import eu.nordtal.s2.papercommon.world.Distances;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.settings.network.NetworkSettings;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.aura.AuraDao;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.NavigateCommand;
import eu.nordtal.s2.smp.command.SmpAdmin;
import eu.nordtal.s2.smp.config.Milestones;
import eu.nordtal.s2.smp.config.MilestonesSpec;
import eu.nordtal.s2.smp.config.SmpSettings;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneState;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.ObjectiveRow;
import eu.nordtal.s2.smp.milestone.StoredProgress;
import eu.nordtal.s2.smp.milestone.TrackDao;
import eu.nordtal.s2.smp.milestone.TrackNames;
import eu.nordtal.s2.smp.milestone.TrackValidation;
import eu.nordtal.s2.smp.navigate.Navigation;
import eu.nordtal.s2.smp.navigate.PlaceDao;
import eu.nordtal.s2.smp.npc.SpawnNpc;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.port.PrizeSource;
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
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

    /** Its own group so a reload can re-read it, unlike the {@code config} group, which is bound once at enable. */
    private Setting<SoundsSpec> soundSettings;

    /** Swapped by a reload; every listener holds this one instance. */
    SmpSounds sounds;

    /** Its own group, taken while running: a change redraws every name tag. */
    private Setting<NameTagConfigurationSpec> nameTagSettings;

    /** Every player's name tag, started at enable. */
    DisplayTags nameTags;

    /** The name colours; volatile, since a reload replaces them and renders read them through a supplier. */
    volatile PrestigeColours prestigeColours;

    /** The track's rows, which the plugin starts over, fills from the settings and reads for the surfaces. */
    TrackDao trackRows;
    /** The aura book, one for every feature that pays or takes aura. */
    AuraDao aura;
    /** The wheel's extra spins, which progress pays and the NPC menu forecasts. */
    PrizeSource prizes;

    Announcer announcer;
    private SmpAdmin admin;
    /** What the last reload refused the track for, or empty when it took it. */
    private volatile List<String> trackProblems = List.of();

    /**
     * The milestone track; volatile, since a reload writes it async and suggestions read it per keystroke.
     */
    volatile MilestoneTrack track;

    Worlds worlds;
    private Boxes balloons;
    final SeasonState season = new SeasonState();

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

    private PresenceListener presence;

    @Override
    protected String settingsPrefix() {
        return "NORDTAL_SMP";
    }

    @Override
    protected String commandRoot() {
        return "smp";
    }

    @Override
    protected void commands(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(console("aura")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("delta", IntegerArgumentType.integer(-10_000, 10_000))
                                        .executes(this::changeAura))))
                .then(console("access")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(this::showAccess)))
                .then(console("objective")
                        .then(Commands.literal("complete")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        // The active milestone's open objectives only; any other key is refused.
                                        .suggests((context, builder) -> suggest(
                                                builder,
                                                season.active().objectives().stream()
                                                        .map(ObjectiveRow::key)
                                                        .toList()))
                                        .executes(this::confirmFirst)
                                        .then(Commands.literal("confirm")
                                                .executes(context -> run(
                                                        context,
                                                        () -> admin.completeObjective(
                                                                StringArgumentType.getString(context, "key"))))))))
                .then(console("nametags").then(Commands.literal("redraw").executes(this::redrawNameTags)))
                .then(console("milestone")
                        .then(Commands.literal("unlock")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> suggest(builder, track.keys()))
                                        .executes(this::confirmFirst)
                                        .then(Commands.literal("confirm")
                                                .executes(context -> run(
                                                        context,
                                                        () -> admin.unlockMilestone(
                                                                StringArgumentType.getString(context, "key"))))))));
    }

    @Override
    protected List<String> bundles() {
        return List.of("messages/smp");
    }

    /** Seen 32 chunks far; simulated at the usual 10, since the simulation is what costs the host memory and ticks. */
    @Override
    protected Distances distanceDefaults() {
        return new Distances(32, 10);
    }

    @Override
    protected void prepare() {
        config = setting(Group.of("config", SmpSpec.class).checkedBy(SmpSettings::check));
        milestoneSettings = setting(Group.of("milestones", MilestonesSpec.class)
                .checkedBy(SmpSettings::checkMilestones)
                .whileRunning());
        soundSettings = setting(Group.of("sounds", SoundsSpec.class).whileRunning());
        nameTagSettings = setting(Group.of("nametags", NameTagConfigurationSpec.class)
                .checkedBy(DisplayTags::check)
                .whileRunning());
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
        trackRows = jdbi().onDemand(TrackDao.class);
        aura = jdbi().onDemand(AuraDao.class);
        // Everything below this line touches the database, so it happens off the main thread.
        PaperScheduler.of(this).execute(this::loadSeasonState);

        announcer = SmpStart.declareHudAndStartAnnouncer(this);

        nameTags = DisplayTags.start(this, nameTagSettings.get());
        final SmpStart.Surfaces wired = SmpStart.wireEffectsAndSurfaces(this, spec);
        // Every name this server holds wears what chat shows: the flag, the prestige colour and the crest.
        composeNames((name, reader) -> identities()
                .held(name.player())
                .map(identity -> wired.composition().chatPrefix(name.name(), identity))
                .orElseGet(() -> Names.BARE.draw(name, reader)));
        boards = wired.boards();
        final SmpStart.Presence inputs = SmpStart.wirePresenceInputs(this, spec, wired);
        cinematics = inputs.cinematics();
        presence = SmpStart.registerPresenceListeners(this, spec, wired, inputs);

        final SmpStart.Progress progress = SmpStart.wireProgressEngine(this, spec, wired.effects());
        prizes = progress.prizes();
        engine = progress.engine();
        poller = progress.poller();
        gates = progress.gates();

        final SmpStart.Activities activities = SmpStart.wireActivities(this, spec, wired.effects());
        graves = activities.graves();
        duels = activities.duels();
        SmpStart.registerActivityListeners(this, spec, activities);
        npc = SmpStart.wireNpc(this, spec);
        balloonDisplay = SmpStart.restoreGravesAndRegisterWorld(this, balloons, regions, wired.effects());

        admin = admin();
        registerCommands(sounds);
        // Before the surfaces, so the pass that starts the track over also draws it; any pass catches a missed switch.
        hub().on(Channel.PHASE, "the season reset", this::startTrackOverIfDue);
        // Surfaces are drawn far more often than their data changes, so they re-read only on its signal.
        hub().on(Channel.SMP, "the boards and HUD", this::refreshSurfaceData);
        answer(SmpRequest.TABLE, request -> switch (request) {
            case SmpRequest.CompleteObjective complete -> admin.completeObjective(complete.key());
            case SmpRequest.UnlockMilestone unlock -> admin.unlockMilestone(unlock.key());
            case SmpRequest.PreviewMessage preview -> preview(preview.player(), preview.preview());
        });
        getLogger()
                .info(track.size() + " milestones, " + regions.all().size() + " protected boxes, "
                        + balloons.all().size() + " balloons");
    }

    @Override
    protected void languageKnown(final Player player) {
        presence.languageKnown(player);
    }

    private void loadMilestoneTrack() {
        final Milestones.Result milestonesResult = Milestones.read(milestoneSettings.get());
        if (!milestonesResult.problems().isEmpty()) {
            milestonesResult.problems().forEach(problem -> getLogger().severe("milestones: " + problem));
        }
        final MilestoneTrack loadedTrack = milestonesResult.track();
        if (loadedTrack == null) {
            throw fatal("smp is not starting: the milestones group could not be parsed into a track at all - "
                    + "see the problem just logged.");
        }
        final List<TrackValidation.Problem> unknown = TrackNames.validate(loadedTrack, TrackNames.Server.running());
        if (!unknown.isEmpty()) {
            unknown.forEach(problem -> getLogger().severe("milestones: " + problem));
            throw fatal("smp is not starting: the milestones group names what this server does not have, see the"
                    + " problems just logged.");
        }
        track = loadedTrack;
    }

    private void loadFeedbackPalettes() {
        // A wrong sound key silences its own category rather than refusing the start.
        sounds = SmpSounds.of(soundSettings.get(), getLogger()::warning);
        prestigeColours = prestigeColours();
    }

    /** The name colours of the network's prestige group, which the base has just read. */
    private PrestigeColours prestigeColours() {
        return PrestigeColours.parse(
                NetworkSettings.prestigeColours(prestigeSettings()),
                prestigeSettings().admin(),
                getLogger()::warning);
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

    @Override
    protected void disable() {
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
        final Boards drawn = boards;
        if (drawn != null) {
            quietly("boards.stop", drawn::stop);
        }
        final DisplayTags tags = nameTags;
        if (tags != null) {
            quietly("nameTags.stop", tags::stop);
        }
    }

    void registerCommands(final SmpSounds sounds) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final NavigateCommand commands = new NavigateCommand(
                    this, jdbi().onDemand(PlaceDao.class), navigation, identities(), renderer(), sounds, this::colours);
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());
        });
    }

    /** The track actions of the console and the inbox, closing and unlocking through the running engine. */
    private SmpAdmin admin() {
        return new SmpAdmin(
                trackRows,
                aura,
                new SmpAdmin.Track() {
                    // Null: an admin's completion has nobody behind it.
                    @Override
                    public void finishObjective(final String milestone, final ObjectiveRow objective) {
                        engine.finishObjective(milestone, objective, null);
                    }

                    @Override
                    public void unlockMilestone(final String milestone) {
                        engine.unlockMilestone(milestone, null);
                    }
                },
                identities(),
                access(),
                getLogger());
    }

    /** {@code /smp aura <player> <delta>}: a correction for somebody online, recorded as the console's. */
    private int changeAura(final CommandContext<CommandSourceStack> context) {
        final Player player = Bukkit.getPlayerExact(StringArgumentType.getString(context, "player"));
        if (player == null) {
            tell(
                    context.getSource().getSender(),
                    Answer.failed(MESSAGES.smp().admin().playerOffline()));
            return Command.SINGLE_SUCCESS;
        }
        final int delta = IntegerArgumentType.getInteger(context, "delta");
        return run(context, () -> admin.changeAura(player.getUniqueId(), player.getName(), delta));
    }

    /** {@code /smp nametags redraw}: drops every name tag and draws it again from the {@code nametags} group. */
    private int redrawNameTags(final CommandContext<CommandSourceStack> context) {
        nameTags.reload(nameTagSettings.get());
        tell(context.getSource().getSender(), Answer.done(MESSAGES.smp().admin().nameTagsRedrawn()));
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /smp access <player>}: whether somebody online is linked, has access and is paying. */
    private int showAccess(final CommandContext<CommandSourceStack> context) {
        final Player player = Bukkit.getPlayerExact(StringArgumentType.getString(context, "player"));
        if (player == null) {
            tell(
                    context.getSource().getSender(),
                    Answer.failed(MESSAGES.smp().admin().playerOffline()));
            return Command.SINGLE_SUCCESS;
        }
        final org.bukkit.command.CommandSender sender = context.getSource().getSender();
        final PaperUser console = PaperUser.console(this, sender, renderer(), this::colours);
        final java.util.UUID id = player.getUniqueId();
        final String name = player.getName();
        PaperScheduler.of(this).execute(() -> {
            try {
                admin.showAccess(console, id, name);
            } catch (final RuntimeException failure) {
                getLogger().log(java.util.logging.Level.WARNING, "/smp access could not read " + name, failure);
                tell(
                        sender,
                        Answer.failed(eu.nordtal.s2.papercommon.PaperCommonMessages.MESSAGES
                                .admin()
                                .failed()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static java.util.concurrent.CompletableFuture<Suggestions> suggest(
            final SuggestionsBuilder builder, final java.util.Collection<String> keys) {
        final String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        keys.stream()
                .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(typed))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** Starts the track over when the phase stamped a fresh start this server has not applied yet. */
    void startTrackOverIfDue() {
        if (trackRows.startOverIfDue() > 0) {
            getLogger().info("the season started over: every milestone is locked and every objective is empty");
        }
    }

    /** Reads the data every surface draws, on the signal hub's thread, so no render waits on the database. */
    void refreshSurfaceData() {
        try {
            java.util.Optional<String> active = trackRows.activeMilestoneKey();
            final List<String> completed = trackRows.completedMilestoneKeys();
            if (!completed.equals(season.completedKeys())
                    || (active.isEmpty() && track.next(completed).isPresent())) {
                // A phase switch started the track over, or nothing has started it yet.
                loadSeasonState();
                active = trackRows.activeMilestoneKey();
            }
            active.ifPresentOrElse(
                    key -> season.refreshActive(key, trackRows.objectivesOf(key)),
                    () -> season.refreshActive(null, java.util.List.of()));
            Objects.requireNonNull(poller).setActiveMilestone(active);
            gates.setActiveMilestone(active);
            Objects.requireNonNull(boards).setLeaderboard(aura.topAura(10));
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
        trackRows
                .activeMilestoneKey()
                .ifPresent(milestoneKey -> trackRows.objectivesOf(milestoneKey).stream()
                        .filter(row -> !row.completed())
                        .filter(row -> row.amount() >= row.target())
                        .forEach(row -> {
                            getLogger()
                                    .info("objective '" + row.key() + "' is already at " + row.amount()
                                            + " of its new target " + row.target() + " - completing it now");
                            engine.finishObjective(milestoneKey, row, null);
                        }));
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
            nameTagSettings.reload();
            nameTags.reload(nameTagSettings.get());
        } catch (final SettingsException | RuntimeException failure) {
            problems.add("the name tags: " + failure.getMessage());
        }
        // The base re-read the prestige group before this.
        prestigeColours = prestigeColours();
        reloadMilestoneTrack();
        problems.addAll(trackProblems);
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
                        candidate, new StoredProgress(trackRows.storedMilestones(), trackRows.storedObjectives())));
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
                PaperScheduler.of(this).execute(this::loadSeasonState);
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
        // The plugin's own DAO joins the transaction this thread holds open.
        jdbi().useTransaction(handle -> {
            for (final Milestone milestone : definition.milestones()) {
                trackRows.ensureMilestone(milestone.key(), MilestoneState.LOCKED.name());
                for (final eu.nordtal.s2.smp.milestone.Objective objective : milestone.objectives()) {
                    trackRows.ensureObjective(
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
        final List<String> completed = trackRows.completedMilestoneKeys();
        // A reset after the read changes the count, so the activation does nothing and the next tick decides.
        if (trackRows.activeMilestoneKey().isEmpty()) {
            now.next(completed).ifPresent(next -> {
                if (trackRows.activateAfter(next.key(), completed.size()) > 0) {
                    getLogger().info("milestone " + next.key() + " is now active");
                }
            });
        }
        season.refresh(completed, now);

        PaperScheduler.of(this).onMain(() -> {
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
