package eu.nordtal.s2.hungergames;

import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.commands.remote.CommandRequests;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.hungergames.body.PlayerBodies;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.command.BukkitHungerGamesEffects;
import eu.nordtal.s2.hungergames.command.HungerGamesCommand;
import eu.nordtal.s2.hungergames.config.HungerGamesCheck;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.config.SoundsSpec;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.hungergames.game.Ceremony;
import eu.nordtal.s2.hungergames.game.HungerGamesManager;
import eu.nordtal.s2.hungergames.game.WinTracker;
import eu.nordtal.s2.hungergames.hud.HudRenderer;
import eu.nordtal.s2.hungergames.listener.CombatListener;
import eu.nordtal.s2.hungergames.listener.FreezeListener;
import eu.nordtal.s2.hungergames.listener.PresenceListener;
import eu.nordtal.s2.hungergames.lobby.Lobby;
import eu.nordtal.s2.hungergames.lobby.LobbyMaps;
import eu.nordtal.s2.hungergames.loot.LootRefill;
import eu.nordtal.s2.hungergames.player.ArenaComposition;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.papercommon.plugin.NordtalPlugin;
import eu.nordtal.s2.settings.Check;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The hunger games start event of season 2. */
public final class HungerGamesPlugin extends NordtalPlugin {

    private Setting<HungerGamesSpec> config;

    /** Its own group, so a reload can re-read it mid-game, unlike {@link #config}. */
    private Setting<SoundsSpec> soundSettings;

    /** Held so a reload can swap what it answers; every listener has this one instance. */
    private HungerGamesSounds sounds;

    private World world;

    /** The season phase as the signal hub last read it; a game only starts during the start event. */
    private volatile SeasonPhase phase = SeasonPhase.PRE_LAUNCH;

    /** The chat effects schedule; the inbox's run inline, since the inbox settles its row when the command returns. */
    private HungerGamesEffects chatEffects;

    private Outbox outbox;
    private @Nullable ScheduledExecutorService commandWaiter;
    private HungerGamesDao dao;

    /** {@code :commands}' bundle as the inbox renders it, a second view of the same files. */
    private @Nullable Messages sharedMessages;

    private final GameState state = new GameState();
    private final PlayerBodies bodies = new PlayerBodies();

    private @Nullable BorderController border;
    private @Nullable LootRefill loot;
    private @Nullable HudRenderer hud;
    private @Nullable Lobby lobby;
    private WinTracker winTracker;
    private Ceremony ceremony;
    private HungerGamesManager manager;
    private PresenceListener presence;

    /** The one game this plugin is currently tracking, refreshed from the database at enable and after decision. */
    private volatile @Nullable UUID currentGameId;

    @Override
    protected String settingsPrefix() {
        return "NORDTAL_HUNGER_GAMES";
    }

    @Override
    protected List<String> bundles() {
        return List.of("messages/commands", "messages/hunger-games");
    }

    @Override
    protected void prepare() {
        config = setting("config", HungerGamesSpec.class, HungerGamesCheck::check);
        soundSettings = setting("sounds", SoundsSpec.class, Check.none());
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
        dao = jdbi().onDemand(HungerGamesDao.class);
        wireGameSystems(config.get());
        wireListeners();
        wireCommands(config.get());
        final PhaseDirectory phases = PhaseDirectory.using(pool(), clock());
        hub().on(Channel.PHASE, "the season phase", () -> phase = phases.currentPhase());
    }

    @Override
    protected void languageKnown(final Player player) {
        presence.languageKnown(player);
    }

    @Override
    protected List<String> reloadOwn() {
        if (sharedMessages != null) {
            sharedMessages.reload();
        }
        return reloadSounds() ? List.of() : List.of("sounds.yml");
    }

    @Override
    protected void disable() {
        if (lobby != null) {
            quietly("lobby.stop", lobby::stop);
        }
        if (hud != null) {
            quietly("hud.stop", hud::stop);
        }
        if (loot != null) {
            quietly("loot.cancelAll", loot::cancelAll);
        }
        if (border != null) {
            quietly("border.stop", border::stop);
        }
        final ScheduledExecutorService waiter = commandWaiter;
        if (waiter != null) {
            // Before the pool: a wait in flight reads the request row through it.
            quietly("commandWaiter.shutdownNow", waiter::shutdownNow);
        }
    }

    /** Builds the border, loot, HUD, lobby, ceremony and manager, and starts the lobby broadcast. */
    private void wireGameSystems(final HungerGamesSpec spec) {
        final BorderController borderController =
                new BorderController(this, world, spec, messages(), locales(), sounds, clock());
        border = borderController;
        final LootRefill refill =
                new LootRefill(this, world, spec, borderController, messages(), locales(), sounds, clock());
        loot = refill;
        // winTracker before hud: the HUD reads the living count off it on every redraw.
        winTracker = new WinTracker(dao, messages(), locales(), sounds, clock());
        hud = new HudRenderer(
                this, world, spec, messages(), locales(), borderController, state, winTracker, refill, clock());
        final Lobby waiting = new Lobby(this, dao, spec, messages(), locales());
        lobby = waiting;
        ceremony = new Ceremony(messages(), locales(), sounds);
        manager = new HungerGamesManager(
                this, dao, spec, messages(), locales(), bodies, state, borderController, sounds, clock());

        refreshCurrentGame();

        // Lobby map slicing, tolerant of missing artwork.
        new LobbyMaps(this, spec).render(world);

        waiting.startBroadcasting(world, () -> currentGameId);
    }

    /** Registers the freeze, presence, system line and combat listeners. */
    private void wireListeners() {
        listen(new FreezeListener(manager));
        // The five system lines; the death line keeps vanilla's own component for killer and weapon.
        final ArenaComposition composition = new ArenaComposition(locales());
        final SystemLines systemLines = new SystemLines(composition::of, messages(), locales());
        listen(systemLines);
        presence = new PresenceListener(this, locales(), bodies, state, messages(), systemLines);
        listen(presence);
        listen(new CombatListener(
                this,
                dao,
                state,
                bodies,
                Objects.requireNonNull(border),
                winTracker,
                sounds,
                systemLines,
                composition,
                this::onGameDecided,
                clock()));
    }

    /** The command layer: two effects instances, the outbox and the command inbox. */
    private void wireCommands(final HungerGamesSpec spec) {
        final Lobby waiting = Objects.requireNonNull(lobby);
        chatEffects = new BukkitHungerGamesEffects(
                this,
                BukkitHungerGamesEffects.async(this),
                dao,
                spec,
                waiting,
                this::currentGameIdNow,
                gameId -> startGame(gameId, world),
                this::reloadSounds,
                this::reloadMessages,
                () -> phase);

        final ScheduledExecutorService waiter = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, getName() + "-command-waiter");
            thread.setDaemon(true);
            return thread;
        });
        commandWaiter = waiter;
        final CommandRequests requests = CommandRequests.over(pool(), clock());
        outbox = new Outbox(
                requests, waiter, (message, failure) -> getLogger().log(Level.WARNING, message, failure), clock());

        // Built here rather than inside the inbox so a reload can swap it too.
        final Messages shared = PaperCommandInbox.sharedBundle(this);
        sharedMessages = shared;
        final PaperCommandInbox inbox = new PaperCommandInbox(this, Target.HUNGER_GAMES, requests, access(), shared);
        // Inline on purpose; see the chatEffects field.
        final HungerGamesEffects inboxEffects = new BukkitHungerGamesEffects(
                this,
                Runnable::run,
                dao,
                spec,
                waiting,
                this::currentGameIdNow,
                gameId -> startGame(gameId, world),
                this::reloadSounds,
                this::reloadMessages,
                () -> phase);
        HungerGamesCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.listen(hub(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final HungerGamesCommand command = new HungerGamesCommand(
                    this, dao, messages(), locales(), waiting, sounds, () -> currentGameId, this::colours);
            command.build(outbox, chatEffects, id -> adminWatch().isAdmin(id))
                    .forEach(node -> event.registrar().register(node));
        });
    }

    /** Runs off the main thread, reading the roster before anyone is released so the first death is tracked. */
    private void startGame(final UUID gameId, final World world) {
        final List<HgMember> activeMembers = dao.activeMembersOf(gameId);
        manager.start(gameId, world, () -> {
            final Instant releasedAt = clock().instant();
            Objects.requireNonNull(loot).scheduleAll(releasedAt);
            Objects.requireNonNull(hud).start();
            winTracker.reset(activeMembers);
        });
    }

    /** Re-reads the message bundles and the operator's override; throws so the console gets the reason. */
    private void reloadMessages() {
        final List<String> problems = reload();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
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

        Objects.requireNonNull(hud).stop();
        Objects.requireNonNull(loot).cancelAll();
        Objects.requireNonNull(border).stop();

        final Location lobbyLocation = new Location(
                found, spec.lobby().x(), spec.lobby().y(), spec.lobby().z());
        // No query: the caller read everything off the main thread, and gameId is still set before state.clear().
        ceremony.run(found, lobbyLocation, Objects.requireNonNull(state.gameId()), decision);
        state.clear();
        decidedGameId = currentGameId;
        currentGameId = null;
    }

    /** The last game this server decided, so a lookup that started before the decision cannot put it back. */
    private volatile @Nullable UUID decidedGameId;

    private void refreshCurrentGame() {
        currentGameId = dao.currentGame().map(game -> game.id()).orElse(null);
    }

    /** The game as the database has it now, for the commands; never called on the main thread. */
    private @Nullable UUID currentGameIdNow() {
        final UUID found = dao.currentGame()
                .map(game -> game.id())
                .filter(id -> !id.equals(decidedGameId))
                .orElse(null);
        // Answer the local value: onGameDecided may clear the field between the query and the return.
        if (found != null) {
            currentGameId = found;
        }
        return found;
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
