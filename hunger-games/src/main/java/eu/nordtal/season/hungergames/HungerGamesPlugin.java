package eu.nordtal.season.hungergames;

import static eu.nordtal.season.hungergames.HungerGamesMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.hungergames.body.PlayerBodies;
import eu.nordtal.season.hungergames.border.BorderController;
import eu.nordtal.season.hungergames.config.HungerGamesCheck;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.hungergames.db.HgGame;
import eu.nordtal.season.hungergames.db.HungerGamesDao;
import eu.nordtal.season.hungergames.db.RosterEntry;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.hungergames.game.Ceremony;
import eu.nordtal.season.hungergames.game.Demotion;
import eu.nordtal.season.hungergames.game.HungerGamesManager;
import eu.nordtal.season.hungergames.game.Names;
import eu.nordtal.season.hungergames.game.Participant;
import eu.nordtal.season.hungergames.game.StartCheck;
import eu.nordtal.season.hungergames.game.WinTracker;
import eu.nordtal.season.hungergames.hud.GameHud;
import eu.nordtal.season.hungergames.listener.CombatListener;
import eu.nordtal.season.hungergames.listener.FreezeListener;
import eu.nordtal.season.hungergames.listener.PresenceListener;
import eu.nordtal.season.hungergames.lobby.Lobby;
import eu.nordtal.season.hungergames.lobby.LobbyMaps;
import eu.nordtal.season.hungergames.loot.LootRefill;
import eu.nordtal.season.hungergames.player.ArenaComposition;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.PaperUser;
import eu.nordtal.season.papercommon.plugin.NordtalPlugin;
import eu.nordtal.season.papercommon.sound.SoundsSpec;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The hunger games start event of season 2. */
public final class HungerGamesPlugin extends NordtalPlugin {

    private Setting<HungerGamesSpec> config;

    /** Its own group, so the settings signal can re-read it mid-game, unlike {@link #config}. */
    private Setting<SoundsSpec> soundSettings;

    /** Held so the settings signal can swap what it answers; every listener has this one instance. */
    private HungerGamesSounds sounds;

    private World world;

    /** The season phase as the signal hub last read it; a game only starts during the start event. */
    private volatile SeasonPhase phase = SeasonPhase.PRE_LAUNCH;

    private HungerGamesDao dao;

    private final GameState state = new GameState();
    private final PlayerBodies bodies = new PlayerBodies();

    private @Nullable BorderController border;
    private @Nullable LootRefill loot;
    private @Nullable GameHud gameHud;
    private @Nullable Lobby lobby;
    private WinTracker winTracker;
    private Ceremony ceremony;
    private HungerGamesManager manager;
    private PresenceListener presence;

    @Override
    protected String settingsPrefix() {
        return "NORDTAL_HUNGER_GAMES";
    }

    @Override
    protected String commandRoot() {
        return "hg";
    }

    @Override
    protected boolean playersUseCommandRoot() {
        // A player marks their own team ready with /hg ready.
        return true;
    }

    @Override
    protected void commands(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("ready")
                        .requires(source -> source.getSender() instanceof Player)
                        .executes(this::markReady))
                .then(console("start")
                        .executes(context -> run(context, () -> startGame(false)))
                        .then(Commands.literal("confirm").executes(context -> run(context, () -> startGame(true)))))
                .then(console("ready-status").executes(this::readyStatus));
    }

    @Override
    protected List<String> bundles() {
        return List.of("messages/hunger-games");
    }

    @Override
    protected void prepare() {
        config = setting(Group.of("config", HungerGamesSpec.class).checkedBy(HungerGamesCheck::check));
        soundSettings = setting(Group.of("sounds", SoundsSpec.class).whileRunning());
        // Built before anything that plays one: a bad key here is reported and the category silenced.
        sounds = HungerGamesSounds.of(soundSettings.get(), getLogger()::warning);
        final World found = resolveWorld(config.get());
        if (found == null) {
            throw fatal(
                    "hunger-games could not find/load world '" + config.get().worldName()
                            + "': stopping the server rather than running an event server with no event world on it");
        }
        world = found;
    }

    @Override
    protected PaperUser.Chime chime() {
        return sounds::play;
    }

    @Override
    protected void enable() {
        // Every name in the arena is drawn alike: the flag and a grey name, so no colour reads as a team.
        composeNames(new ArenaComposition(identities()));
        dao = jdbi().onDemand(HungerGamesDao.class);
        wireGameSystems(config.get());
        wireListeners();
        answer(HungerGamesRequest.TABLE, request -> switch (request) {
            case HungerGamesRequest.StartGame start -> startGame(start.confirmed());
            case HungerGamesRequest.PreviewMessage preview -> preview(preview.player(), preview.preview());
        });
        final PhaseDirectory phases = PhaseDirectory.using(pool(), clock());
        hub().on(Channel.PHASE, "the season phase", () -> phase = phases.currentPhase());
    }

    @Override
    protected void languageKnown(final Player player) {
        presence.languageKnown(player);
    }

    @Override
    protected List<String> reloadOwn() {
        return reloadSounds() ? List.of() : List.of("the sounds");
    }

    @Override
    protected void disable() {
        if (lobby != null) {
            quietly("lobby.stop", lobby::stop);
        }
        if (loot != null) {
            quietly("loot.cancelAll", loot::cancelAll);
        }
        if (border != null) {
            quietly("border.stop", border::stop);
        }
    }

    /** Builds the border, loot, HUD, lobby, ceremony and manager, and starts the lobby broadcast. */
    private void wireGameSystems(final HungerGamesSpec spec) {
        final BorderController borderController =
                new BorderController(this, world, spec, renderer(), identities(), sounds, clock());
        border = borderController;
        final LootRefill refill =
                new LootRefill(this, world, spec, borderController, renderer(), identities(), sounds, clock());
        loot = refill;
        // winTracker before the HUD, which reads the living count off it on every redraw.
        winTracker = new WinTracker(dao, renderer(), identities(), sounds, clock());
        final GameHud lines =
                new GameHud(world, spec, renderer().raw(), borderController, state, winTracker, refill, clock());
        lines.declareOn(hud());
        gameHud = lines;
        final Lobby waiting = new Lobby(this, dao, spec, renderer(), identities());
        lobby = waiting;
        ceremony = new Ceremony(renderer(), identities(), sounds);
        manager = new HungerGamesManager(
                this, dao, spec, renderer(), identities(), bodies, state, borderController, sounds, clock());

        abortInterrupted();

        // Lobby map slicing, tolerant of missing artwork.
        new LobbyMaps(this, spec, renderer().raw().locales()).render(world);

        waiting.startBroadcasting(world);
    }

    /** Registers the freeze, presence, system line and combat listeners. */
    private void wireListeners() {
        listen(new FreezeListener(manager));
        // The five system lines; the death line keeps the game's own line for killer and weapon.
        final SystemLines systemLines = systemLines();
        presence = new PresenceListener(this, identities(), bodies, state, renderer(), systemLines, players());
        listen(presence);
        listen(new CombatListener(
                this,
                dao,
                state,
                bodies,
                Objects.requireNonNull(border),
                winTracker,
                sounds,
                new Names(access()::identities),
                systemLines,
                this::onGameDecided,
                clock()));
    }

    /**
     * Starts a game from the open round, or says why not; never on the main thread.
     *
     * Below the recommended minimum only a confirmed start goes ahead, since the asker has seen the numbers.
     */
    private Answer startGame(final boolean confirmed) {
        final Optional<HgGame> underWay = dao.gameUnderWay();
        final Optional<UUID> round = dao.openRound();
        final int participants =
                round.map(open -> Demotion.resolve(dao.roster(open)).size()).orElse(0);
        final int recommended = config.get().softMinimumParticipants();
        final Optional<Refusal> refused = StartCheck.refusal(
                underWay.map(HgGame::state).orElse(null),
                round.isPresent(),
                phase,
                participants,
                recommended,
                confirmed);
        if (refused.isPresent() || round.isEmpty()) {
            return Answer.refused(refused.orElseGet(ServerRefusal.NO_GAME::with));
        }
        final Optional<UUID> started = dao.startGame(round.get());
        if (started.isEmpty()) {
            // Another start closed the round between the check and here.
            return Answer.refused(
                    ServerRefusal.WRONG_STATE.with(eu.nordtal.season.hungergames.db.GameState.COUNTDOWN.name()));
        }
        // The round is closed now, so this roster is the one the game is played with.
        final List<Participant> playing = Demotion.resolve(dao.roster(round.get()));
        getLogger()
                .info("game " + started.get() + " started with " + playing.size() + " resolvable participants"
                        + (playing.size() < recommended ? " (confirmed below the recommended minimum)" : ""));
        startGame(started.get(), playing);
        return Answer.done(MESSAGES.hg().admin().started(playing.size()));
    }

    /** {@code /hg ready-status}: every registered team and whether it has said it is ready. */
    private int readyStatus(final CommandContext<CommandSourceStack> context) {
        final PaperUser console = PaperUser.console(this, context.getSource().getSender(), renderer(), this::colours);
        final Lobby waiting = Objects.requireNonNull(lobby);
        PaperScheduler.of(this).execute(() -> {
            final Optional<List<RosterEntry>> roster = waiting.readyStatus();
            if (roster.isEmpty()) {
                console.reply(ServerRefusal.NO_GAME.with().message(), Tone.WARN);
                return;
            }
            final Map<String, Boolean> byTeam = new LinkedHashMap<>();
            for (final RosterEntry entry : roster.get()) {
                byTeam.merge(entry.teamName(), entry.ready(), (one, two) -> one && two);
            }
            console.reply(MESSAGES.hg().admin().readyHeader(), Tone.NEUTRAL);
            // The admin is looking for who is NOT ready yet, so the tone carries the answer.
            byTeam.forEach((team, ready) -> console.reply(
                    MESSAGES.hg().admin().readyLine(new TeamContext(team), ready), ready ? Tone.GOOD : Tone.MUTED));
        });
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /hg ready}: marks the sender's team ready, which only a player can be. */
    private int markReady(final CommandContext<CommandSourceStack> context) {
        final Player player = (Player) context.getSource().getSender();
        final PaperUser user = PaperUser.of(
                this,
                player,
                identities().languageOf(player.getUniqueId()),
                false,
                java.util.Optional::<eu.nordtal.season.common.id.DiscordId>empty,
                renderer(),
                sounds::play,
                this::colours);
        final Lobby waiting = Objects.requireNonNull(lobby);
        PaperScheduler.of(this).execute(() -> {
            final var discordId = identities().discordIdOf(player.getUniqueId());
            final boolean marked = discordId.isPresent() && waiting.markReady(discordId.get());
            user.reply(
                    marked
                            ? MESSAGES.hg().lobby().readySet()
                            : MESSAGES.hg().lobby().notRegistered(),
                    marked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED,
                    marked ? Tone.GOOD : Tone.BAD);
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Runs off the main thread, so the names are read before anyone stands on a tower. */
    private void startGame(final UUID gameId, final List<Participant> participants) {
        final Map<UUID, PlayerContext> names = new Names(access()::identities)
                .of(participants.stream().map(Participant::mcUuid).toList());
        manager.start(gameId, participants, names, world, () -> {
            final Instant releasedAt = clock().instant();
            Objects.requireNonNull(loot).scheduleAll(releasedAt);
            Objects.requireNonNull(gameHud).show();
            winTracker.reset(participants);
        });
    }

    private boolean reloadSounds() {
        try {
            soundSettings.reload();
            sounds.reload(soundSettings.get());
            getLogger().info("the sounds were reloaded");
            return true;
        } catch (final SettingsException | RuntimeException exception) {
            getLogger()
                    .severe("the sounds could not be reloaded, the running ones are unchanged: "
                            + exception.getMessage());
            return false;
        }
    }

    private void onGameDecided(final Ceremony.Decision decision) {
        final HungerGamesSpec spec = config.get();
        final World found = resolveWorld(spec);
        if (found == null) {
            return;
        }

        Objects.requireNonNull(gameHud).hide();
        Objects.requireNonNull(loot).cancelAll();
        Objects.requireNonNull(border).stop();

        final Location lobbyLocation = new Location(
                found, spec.lobby().x(), spec.lobby().y(), spec.lobby().z());
        // No query: the caller read everything off the main thread, and gameId is still set before state.clear().
        ceremony.run(found, lobbyLocation, Objects.requireNonNull(state.gameId()), decision);
        state.clear();
    }

    /** Aborts a game the last stop interrupted, so its round is open again and an admin starts it anew. */
    private void abortInterrupted() {
        dao.abortInterrupted()
                .ifPresent(game -> getLogger()
                        .warning("game " + game.id() + " was in " + game.state() + " when the server stopped:"
                                + " it is aborted and its round is open again for an admin to start anew"));
    }

    private @Nullable World resolveWorld(final HungerGamesSpec config) {
        final World world = Bukkit.getWorld(config.worldName());
        if (world == null) {
            getLogger()
                    .warning("World '" + config.worldName() + "' is not currently loaded - "
                            + "hunger-games cannot run without its event world");
        }
        return world;
    }
}
