package eu.nordtal.season.smp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.displaytags.DisplayTags;
import eu.nordtal.season.displaytags.config.spec.NameTagConfigurationSpec;
import eu.nordtal.season.messagerendering.Names;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.PaperUser;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.plugin.NordtalPlugin;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.papercommon.world.Distances;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.smp.announce.Announcer;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.board.Boards;
import eu.nordtal.season.smp.command.NavigateCommand;
import eu.nordtal.season.smp.command.SmpAdmin;
import eu.nordtal.season.smp.config.AdvancementAwardsSpec;
import eu.nordtal.season.smp.config.BoardsSpec;
import eu.nordtal.season.smp.config.BorderSpec;
import eu.nordtal.season.smp.config.DeathsSpec;
import eu.nordtal.season.smp.config.DuelsSpec;
import eu.nordtal.season.smp.config.Milestones;
import eu.nordtal.season.smp.config.MilestonesSpec;
import eu.nordtal.season.smp.config.NpcSpec;
import eu.nordtal.season.smp.config.SmpSettings;
import eu.nordtal.season.smp.config.SpawnSpec;
import eu.nordtal.season.smp.config.WheelSpec;
import eu.nordtal.season.smp.config.WorldsSpec;
import eu.nordtal.season.smp.duel.Duels;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.grave.Graves;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneState;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.milestone.StoredProgress;
import eu.nordtal.season.smp.milestone.TrackDao;
import eu.nordtal.season.smp.milestone.TrackNames;
import eu.nordtal.season.smp.milestone.TrackValidation;
import eu.nordtal.season.smp.navigate.Navigation;
import eu.nordtal.season.smp.navigate.PlaceDao;
import eu.nordtal.season.smp.npc.SpawnNpc;
import eu.nordtal.season.smp.player.PlayerSurfacesListener;
import eu.nordtal.season.smp.prestige.PrestigeColours;
import eu.nordtal.season.smp.progress.GateHolders;
import eu.nordtal.season.smp.progress.ObjectiveEngine;
import eu.nordtal.season.smp.progress.StatisticPoller;
import eu.nordtal.season.smp.region.Boxes;
import eu.nordtal.season.smp.region.ConfigBoxes;
import eu.nordtal.season.smp.stage.BukkitCinematics;
import eu.nordtal.season.smp.state.SeasonState;
import eu.nordtal.season.smp.wheel.SpinDao;
import eu.nordtal.season.smp.world.Datapacks;
import eu.nordtal.season.smp.world.Worlds;
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

/** A refusal at start stops the server. */
public final class SmpPlugin extends NordtalPlugin {

    /** Applies at the next start: a running server keeps the worlds and datapacks it loaded. */
    private Setting<WorldsSpec> worldsSettings;

    Setting<BorderSpec> borderSettings;
    Setting<WheelSpec> wheelSettings;
    Setting<AdvancementAwardsSpec> awardSettings;
    Setting<DuelsSpec> duelSettings;
    Setting<BoardsSpec> boardSettings;
    Setting<NpcSpec> npcSettings;
    Setting<SpawnSpec> spawnSettings;
    Setting<DeathsSpec> deathSettings;

    /** The protected spawn regions, rebuilt when the spawn group changes. */
    volatile Boxes spawnRegions;

    private Setting<MilestonesSpec> milestoneSettings;

    /** A group of its own, so a reload can read it again. */
    private Setting<SoundsSpec> soundSettings;

    /** Reloaded in place by {@code reloadOwn}. */
    SmpSounds sounds;

    /** A group of its own that applies while the server runs. A change redraws every name tag. */
    private Setting<NameTagConfigurationSpec> nameTagSettings;

    DisplayTags nameTags;

    volatile PrestigeColours prestigeColours;

    TrackDao trackRows;
    AuraDao aura;

    Announcer announcer;
    private SmpAdmin admin;
    /** The problems of the last track reload, or empty when the reload took the track. */
    private volatile List<String> trackProblems = List.of();

    /** A reload replaces the track. */
    volatile MilestoneTrack track;

    Worlds worlds;
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

    @Nullable
    BukkitCinematics cinematics;

    private PlayerSurfacesListener presence;

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
        root.then(console("access")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(this::showAccess)))
                .then(console("objective")
                        .then(Commands.literal("complete")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        // Suggests the objectives of the active milestone. Any other key is refused.
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

    @Override
    protected Distances distanceDefaults() {
        return new Distances(32, 10);
    }

    @Override
    protected void prepare() {
        worldsSettings = setting(Group.of("worlds", WorldsSpec.class).checkedBy(SmpSettings::checkWorlds));
        borderSettings = setting(Group.of("border", BorderSpec.class)
                .checkedBy(SmpSettings::checkBorder)
                .whileRunning());
        wheelSettings = setting(Group.of("wheel", WheelSpec.class)
                .checkedBy(SmpSettings::checkWheel)
                .whileRunning());
        awardSettings = setting(Group.of("advancement-awards", AdvancementAwardsSpec.class)
                .checkedBy(SmpSettings::checkAdvancementAwards)
                .whileRunning());
        duelSettings = setting(Group.of("duels", DuelsSpec.class)
                .checkedBy(SmpSettings::checkDuels)
                .whileRunning());
        boardSettings = setting(Group.of("boards", BoardsSpec.class)
                .checkedBy(SmpSettings::checkBoards)
                .whileRunning());
        npcSettings = setting(Group.of("npc", NpcSpec.class).whileRunning());
        spawnSettings = setting(Group.of("spawn", SpawnSpec.class)
                .checkedBy(SmpSettings::checkSpawn)
                .whileRunning());
        deathSettings = setting(Group.of("deaths-and-graves", DeathsSpec.class)
                .checkedBy(SmpSettings::checkDeaths)
                .whileRunning());
        milestoneSettings = setting(Group.of("milestones", MilestonesSpec.class)
                .checkedBy(SmpSettings::checkMilestones)
                .whileRunning());
        soundSettings = setting(Group.of("sounds", SoundsSpec.class).whileRunning());
        nameTagSettings = setting(Group.of("nametags", NameTagConfigurationSpec.class)
                .checkedBy(DisplayTags::check)
                .whileRunning());
        loadMilestoneTrack();
        loadFeedbackPalettes();
        worlds = bootstrapWorlds(worldsSettings.get(), spawnSettings.get());
    }

    @Override
    protected boolean refusesWithoutIdentity() {
        // A player that the plugin cannot identify would lose progress, so the login is refused.
        return true;
    }

    @Override
    protected PaperUser.Chime chime() {
        return sounds::play;
    }

    @Override
    protected void enable() {
        spawnRegions = ConfigBoxes.of(spawnSettings.get().spawnRegions());
        trackRows = jdbi().onDemand(TrackDao.class);
        aura = jdbi().onDemand(AuraDao.class);
        PaperScheduler.of(this).execute(this::loadSeasonState);

        announcer = SmpStart.declareHudAndStartAnnouncer(this);

        nameTags = DisplayTags.start(this, nameTagSettings.get());
        final SmpStart.Surfaces wired = SmpStart.wireEffectsAndSurfaces(this);
        // A name with an identity gets the chat prefix: flag, prestige colour and crest.
        composeNames((name, reader) -> identities()
                .held(name.player())
                .map(identity -> wired.composition().chatPrefix(name.name(), identity))
                .orElseGet(() -> Names.BARE.draw(name, reader)));
        boards = wired.boards();
        final SmpStart.Presence inputs = SmpStart.wirePresenceInputs(this, wired);
        cinematics = inputs.cinematics();
        presence = SmpStart.registerSurfaceListener(this, wired, inputs);

        final SmpStart.Progress progress = SmpStart.wireProgressEngine(this, wired.effects());
        engine = progress.engine();
        poller = progress.poller();
        gates = progress.gates();

        final SmpStart.Activities activities = SmpStart.wireActivities(this, wired.effects());
        graves = activities.graves();
        duels = activities.duels();
        SmpStart.registerActivityListeners(this, activities);
        npc = SmpStart.wireNpc(this);
        SmpStart.restoreGravesAndRegisterWorld(this);

        admin = admin();
        registerCommands(sounds);
        // This handler runs before the surface handler, so one pass starts the track over and draws it.
        hub().on(Channel.PHASE, "the season reset", this::startTrackOverIfDue);
        hub().on(Channel.SMP, "the boards and HUD", this::refreshSurfaceData);
        answer(SmpRequest.TABLE, (request, actor) -> switch (request) {
            case SmpRequest.CompleteObjective complete -> admin.completeObjective(complete.key());
            case SmpRequest.UnlockMilestone unlock -> admin.unlockMilestone(unlock.key());
            case SmpRequest.SetAura set -> admin.setAura(set.person(), set.aura(), actor);
            case SmpRequest.SetSpins set -> admin.setSpins(set.person(), set.spins());
            case SmpRequest.PreviewMessage preview -> preview(preview.player(), preview.preview());
            case SmpRequest.PreviewDocument preview -> preview(preview.player(), preview.preview());
            case SmpRequest.CheckSettings check -> checkSettings(check.change());
            case SmpRequest.StoreModeState store -> storeModeState(store.player());
            case SmpRequest.OpenInventory open -> openEditor(open.admin(), open.player(), open.container());
            case SmpRequest.RemoveStack remove ->
                removeStack(
                        remove.player(),
                        remove.mode(),
                        remove.container(),
                        remove.slot(),
                        remove.item(),
                        remove.amount());
        });
        getLogger().info(track.size() + " milestones, " + spawnRegions.all().size() + " protected boxes");
    }

    @Override
    protected void languageKnown(final Player player) {
        presence.languageKnown(player);
    }

    private void loadMilestoneTrack() {
        final Milestones.Result milestonesResult = Milestones.read(milestoneSettings.get());
        final String refusal =
                milestonesRefusal(milestonesResult, problem -> getLogger().severe("milestones: " + problem));
        if (refusal != null) {
            throw fatal("smp is not starting: " + refusal + ", see the problems just logged.");
        }
        track = Objects.requireNonNull(milestonesResult.track());
    }

    @Override
    protected <T> @Nullable String refusesAtStart(final Group<T> group, final T value) {
        if (!(value instanceof final MilestonesSpec milestones)) {
            return null;
        }
        final List<String> problems = new ArrayList<>();
        final String refusal = milestonesRefusal(Milestones.read(milestones), problems::add);
        return refusal == null ? null : refusal + ": " + String.join("; ", problems);
    }

    /** Returns why the server does not start with this track, or null, and hands each problem to {@code problems}. */
    private static @Nullable String milestonesRefusal(
            final Milestones.Result result, final java.util.function.Consumer<String> problems) {
        result.problems().forEach(problem -> problems.accept(String.valueOf(problem)));
        final MilestoneTrack loadedTrack = result.track();
        if (loadedTrack == null) {
            return "the milestones group could not be parsed into a track at all";
        }
        final List<TrackValidation.Problem> unknown = TrackNames.validate(loadedTrack, TrackNames.Server.running());
        unknown.forEach(problem -> problems.accept(String.valueOf(problem)));
        return unknown.isEmpty() ? null : "the milestones group names what this server does not have";
    }

    private void loadFeedbackPalettes() {
        // An invalid sound key silences its category and does not refuse the start.
        sounds = SmpSounds.of(soundSettings.get(), getLogger()::warning);
        prestigeColours = prestigeColours();
    }

    private PrestigeColours prestigeColours() {
        return PrestigeColours.parse(
                NetworkSettings.prestigeColours(prestigeSettings()),
                prestigeSettings().admin(),
                getLogger()::warning);
    }

    private Worlds bootstrapWorlds(final WorldsSpec config, final SpawnSpec spawn) {
        final Datapacks.Result packs = Datapacks.check(config.requiredDatapacks());
        if (!packs.ok()) {
            throw fatal("smp is not starting: " + packs.describe() + ". Datapacks are read once at server "
                    + "start, so installing them now would not change any terrain - put them in the "
                    + "level-name world's datapacks/ folder and restart.");
        }

        final Worlds candidate = new Worlds(config, borderSettings.get());
        final World nordtal = candidate.bootstrap().orElse(null);
        if (nordtal == null) {
            throw fatal("smp is not starting: the world '" + config.worldNordtal() + "' does not exist. "
                    + "It carries the built spawn, so an empty replacement would hide a broken "
                    + "deployment behind a world nobody recognises.");
        }

        // A missing first-join world is a warning, not a refusal.
        if (Bukkit.getWorld(spawn.firstJoinSpawn().world()) == null) {
            getLogger()
                    .warning("first-join-spawn names the world '"
                            + spawn.firstJoinSpawn().world() + "', which does not exist on this server. "
                            + "First joins will not be moved anywhere. The build world is called '"
                            + config.worldNordtal() + "'.");
        }
        candidate.applyFixedBorders();
        return candidate;
    }

    @Override
    protected void disable() {
        // A running staging leaves a potion effect on the player.
        final BukkitCinematics staging = cinematics;
        if (staging != null) {
            quietly("cinematics.stop", staging::stop);
        }
        final SpawnNpc figure = npc;
        if (figure != null) {
            quietly("npc.remove", figure::remove);
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
                    this,
                    jdbi().onDemand(PlaceDao.class),
                    navigation,
                    identities(),
                    renderer(),
                    documents(),
                    sounds,
                    this::colours);
            event.registrar().register(commands.navigate());
            event.registrar().register(commands.poi());
        });
    }

    /** Returns the admin actions of the console and the inbox. */
    private SmpAdmin admin() {
        return new SmpAdmin(
                trackRows,
                aura,
                jdbi().onDemand(SpinDao.class),
                new SmpAdmin.Track() {
                    // null: no player completed it.
                    @Override
                    public void finishObjective(final String milestone, final ObjectiveRow objective) {
                        engine.finishObjective(milestone, objective, null);
                    }

                    @Override
                    public void unlockMilestone(final String milestone) {
                        engine.unlockMilestone(milestone, null);
                    }
                },
                access(),
                getLogger());
    }

    private int redrawNameTags(final CommandContext<CommandSourceStack> context) {
        nameTags.reload(nameTagSettings.get());
        tell(context.getSource().getSender(), Answer.done(Sent.of(SmpAdmin.NAME_TAGS_REDRAWN)));
        return Command.SINGLE_SUCCESS;
    }

    /** Reports whether an online player is linked, has access and is paying. */
    private int showAccess(final CommandContext<CommandSourceStack> context) {
        final Player player = Bukkit.getPlayerExact(StringArgumentType.getString(context, "player"));
        if (player == null) {
            tell(context.getSource().getSender(), Answer.failed(Sent.of(SmpAdmin.PLAYER_OFFLINE)));
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
                tell(sender, Answer.failed(Sent.of(Replies.FAILED)));
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

    /** Starts the track over if the phase marked a fresh start that is not applied yet. */
    void startTrackOverIfDue() {
        if (trackRows.startOverIfDue() > 0) {
            getLogger().info("the season started over: every milestone is locked and every objective is empty");
        }
    }

    /** Reads the data of the surfaces on the thread of the signal hub. */
    void refreshSurfaceData() {
        try {
            java.util.Optional<String> active = trackRows.activeMilestoneKey();
            final List<String> completed = trackRows.completedMilestoneKeys();
            if (!completed.equals(season.completedKeys())
                    || (active.isEmpty() && track.next(completed).isPresent())) {
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
            // The surfaces keep their last data.
            getLogger().warning("could not refresh the boards and HUD: " + exception);
        }
    }

    /** Finishes every objective of the active milestone whose amount reaches its reloaded target. */
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
        // Each group fails on its own.
        final List<String> problems = new ArrayList<>();
        reload(problems, "the sounds", soundSettings, () -> sounds.reload(soundSettings.get()));
        reload(problems, "the name tags", nameTagSettings, () -> nameTags.reload(nameTagSettings.get()));
        final PaperScheduler scheduler = PaperScheduler.of(this);
        reload(problems, "the border", borderSettings, () -> scheduler.onMain(worlds::applyFixedBorders));
        // The boards draw themselves again on the next refresh.
        reload(problems, "the boards", boardSettings, () -> scheduler.onMain(Objects.requireNonNull(boards)::stop));
        reload(problems, "the NPC", npcSettings, () -> scheduler.onMain(Objects.requireNonNull(npc)::spawn));
        reload(
                problems,
                "the spawn",
                spawnSettings,
                () -> spawnRegions = ConfigBoxes.of(spawnSettings.get().spawnRegions()));
        // These are read where they are used, so a reload alone applies them.
        reload(problems, "the wheel", wheelSettings, () -> {});
        reload(problems, "the advancement awards", awardSettings, () -> {});
        reload(problems, "the duels", duelSettings, () -> {});
        reload(problems, "the deaths and graves", deathSettings, () -> {});
        prestigeColours = prestigeColours();
        reloadMilestoneTrack();
        problems.addAll(trackProblems);
        return problems;
    }

    /** Reloads one group and runs {@code applied} if its values changed. A failure lands in {@code problems}. */
    private static void reload(
            final List<String> problems, final String label, final Setting<?> setting, final Runnable applied) {
        try {
            if (setting.reload()) {
                applied.run();
            }
        } catch (final SettingsException | RuntimeException failure) {
            problems.add(label + ": " + failure.getMessage());
        }
    }

    private void reloadMilestoneTrack() {
        try {
            milestoneSettings.reload();
            final Milestones.Result reloaded = Milestones.read(milestoneSettings.get());
            final MilestoneTrack candidate = reloaded.track();

            final List<TrackValidation.Problem> problems;
            if (candidate == null) {
                // The track could not be parsed.
                problems = reloaded.problems();
            } else {
                problems = new ArrayList<>(TrackValidation.validate(
                        candidate, new StoredProgress(trackRows.storedMilestones(), trackRows.storedObjectives())));
                problems.addAll(TrackNames.validate(candidate, TrackNames.Server.running()));
            }
            if (!problems.isEmpty() || candidate == null) {
                getLogger().severe("the milestone track was NOT reloaded, the running track is unchanged:");
                problems.forEach(problem -> getLogger().severe("  " + problem));
                trackProblems =
                        problems.stream().map(TrackValidation.Problem::toString).toList();
            } else {
                // ensureRows runs first, so the track stays unchanged if it throws.
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
     * Writes one row per milestone and one per objective in one transaction.
     * The transaction keeps the targets that {@code ObjectiveEngine#credit} reads from being half updated.
     */
    private void ensureRows(final MilestoneTrack definition) {
        jdbi().useTransaction(handle -> {
            for (final Milestone milestone : definition.milestones()) {
                trackRows.ensureMilestone(milestone.key(), MilestoneState.LOCKED.name());
                for (final eu.nordtal.season.smp.milestone.Objective objective : milestone.objectives()) {
                    trackRows.ensureObjective(
                            milestone.key(), objective.key(), objective.type().name(), objective.target());
                }
            }
        });
    }

    /** Reads the track progress and sets the border of Nordtal to the size of the completed milestones. */
    void loadSeasonState() {
        final MilestoneTrack now = track;
        ensureRows(now);
        final List<String> completed = trackRows.completedMilestoneKeys();
        // If a reset happens after the read, the count differs and the activation changes nothing.
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
}
