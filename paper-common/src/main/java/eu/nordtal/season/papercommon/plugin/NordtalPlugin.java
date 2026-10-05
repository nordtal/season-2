package eu.nordtal.season.papercommon.plugin;

import static eu.nordtal.season.papercommon.PaperCommonMessages.MESSAGES;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.health.Shutdown;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AdminOperators;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.database.command.CommandTreeStore;
import eu.nordtal.season.database.command.CommandTreeWriter;
import eu.nordtal.season.database.game.GameCatalogue;
import eu.nordtal.season.database.game.GameDataStore;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxTable;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.Names;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.papercommon.access.AdminWatch;
import eu.nordtal.season.papercommon.access.BukkitOps;
import eu.nordtal.season.papercommon.chat.Previews;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.CommandFilter;
import eu.nordtal.season.papercommon.command.CommandTrees;
import eu.nordtal.season.papercommon.command.PaperUser;
import eu.nordtal.season.papercommon.game.GameDataExport;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.papercommon.menu.Menus;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.player.Presence;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.papercommon.world.Distances;
import eu.nordtal.season.papercommon.world.WorldDistances;
import eu.nordtal.season.settings.Colours;
import eu.nordtal.season.settings.ColoursSpec;
import eu.nordtal.season.settings.DatabasePool;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.DistancesSpec;
import eu.nordtal.season.settings.Environment;
import eu.nordtal.season.settings.EnvironmentSettings;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.Settings;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.LanguageAndTimeSpec;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.settings.network.PlayersSpec;
import eu.nordtal.season.settings.network.PrestigeSpec;
import eu.nordtal.season.settings.network.SeasonSpec;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one start and stop every Nordtal Paper plugin shares; a plugin contributes its settings, bundles and features.
 * A failed start stops the server, since a healthy container without its season does damage nobody sees.
 */
public abstract class NordtalPlugin extends JavaPlugin {

    // Counts instants until the settings name the zone; replaced once, before anything is shown.
    private Clock clock = NetworkTime.clock();

    private DatabaseSettings settings;
    private Setting<DatabaseSpec> database;
    private Setting<ColoursSpec> colourSettings;
    private Setting<DistancesSpec> distanceSettings;
    private Setting<PlayersSpec> players;
    private Setting<PrestigeSpec> prestigeSettings;
    private volatile Prestige prestige;
    private SeasonSpec season;
    private LanguageAndTimeSpec languageAndTime;
    private WorldDistances worldDistances;
    private volatile ToneColours colours;
    private Messages messages;
    // The bare name until the plugin's enable() composes its own; read on every name drawn, from any thread.
    private volatile Names names = Names.BARE;
    private MessageRenderer renderer;
    private Previews previews;
    private HikariDataSource pool;
    private Jdbi jdbi;
    private AccessReader access;
    private Identities identities;
    private Hud hud;
    private Menus menus;
    private AdminWatch adminWatch;
    private CommandTrees commandTrees;
    private @Nullable SignalHub hub;

    /** Returns the prefix of every environment override of this plugin's settings, {@code NORDTAL_SMP} say. */
    protected abstract String settingsPrefix();

    /** Returns this plugin's message bundle roots, most general first, loaded above {@code paper-common}'s. */
    protected abstract List<String> bundles();

    /** Returns the root of this plugin's commands, {@code smp} for {@code /smp}. */
    protected abstract String commandRoot();

    /** Returns whether players reach anything under the command root; otherwise only the console sees it. */
    protected boolean playersUseCommandRoot() {
        return false;
    }

    /** Adds this plugin's subcommands to its root. */
    protected void commands(final LiteralArgumentBuilder<CommandSourceStack> root) {}

    /** Loads this plugin's own settings and refuses what must hold before its features start, worlds say. */
    protected abstract void prepare();

    /** Wires this plugin's features onto the running base; refreshes registered on {@link #hub()} run in order. */
    protected abstract void enable();

    /** Stops this plugin's features before the base closes the hub and the pool; wrap each step in quietly. */
    protected void disable() {}

    /** Returns whether a login whose identity cannot be read is refused rather than let in as nobody. */
    protected boolean refusesWithoutIdentity() {
        return false;
    }

    /** Runs on the main thread one tick after a player joined, once every join handler ran: the moment to greet. */
    protected void languageKnown(final Player player) {}

    /** Returns how this plugin sounds a refusal; silent unless it has sounds. */
    protected PaperUser.Chime chime() {
        return PaperUser.Chime.silent();
    }

    /** Returns the distances every world runs with where an admin sets none; the server's own unless overridden. */
    protected Distances distanceDefaults() {
        return Distances.NONE;
    }

    /** Re-reads this plugin's own settings for a reload, after the base re-read the colours. */
    protected List<String> reloadOwn() {
        return List.of();
    }

    @Override
    public final void onEnable() {
        // First: loads what every disable step needs while the jar still exists.
        Shutdown.warmUp();
        try {
            start();
        } catch (final Fatal fatal) {
            // Already logged, and the shutdown is under way.
        } catch (final RuntimeException failure) {
            final RuntimeException _ = fatal(getName() + " is not starting: " + failure.getMessage());
        }
    }

    private void start() {
        takeSettings();

        // The worlds are loaded by now; one a plugin loads later gets the same through the listener.
        worldDistances = new WorldDistances(wantedDistances(), logger());
        worldDistances.apply(getServer().getWorlds());
        listen(worldDistances);

        messages = loadMessages();

        jdbi = Jdbis.over(pool);
        access = AccessReader.using(pool, clock);
        identities = new Identities(access::identities);
        renderer = MessageRenderer.of(
                messages, (name, reader) -> names.draw(name, reader), identities.cards(this::prestige));
        previews = new Previews(getServer()::getPlayer, renderer);
        hud = new Hud(this, identities);
        listen(hud);
        menus = new Menus(chime());
        listen(menus);

        // ops.json survives a crash, so an admin left in it is swept before any join is handled.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();
        listen(new Presence(
                this, identities, operators, renderer, refusesWithoutIdentity(), this::languageKnown, logger()));
        adminWatch = new AdminWatch(this, access, operators, logger());
        filterCommands(adminWatch);
        // Only the proxy enforces the network's limit, so this server takes whoever it sends.
        listen(new Unbounded());

        final SignalHub signals = openHub();
        hub = signals;
        commandTrees = new CommandTrees(this, new CommandTreeWriter(CommandTreeStore.using(pool), getName()));
        listen(commandTrees);
        registerCommands();
        enable();
        // After enable, which declared the lines; a plugin that declared none has no clock.
        hud.start();
        adminWatch.listen(signals);
        // Every signal runs every refresh, so an aura booked on smp's channel lands here too.
        signals.on(Channel.ADMIN, "who the players are", identities::reread);
        // An admin's change in Steward reaches this process as a reload, on the hub's thread.
        settings.listen(signals, this::reload);
        // The hub reads the overrides as it connects, off the main thread; until then the packaged texts show.
        MessageOverrideStore.using(pool).follow(messages, signals);
        signals.start();
        exportGameData();

        // Last, so a marker means every step above ran; off the main thread, so a frozen one lets it go stale.
        final var _ = Readiness.onDefaultPath(clock, getLogger()::warning).keepBeating(PaperScheduler.of(this));
        getLogger().info(getName() + " enabled");
    }

    /**
     * Publishes what this server knows of the game for Steward's pickers, read on the first tick and written off it.
     *
     * The first tick is after every datapack is loaded; a failed write costs the pickers this server's entries.
     */
    private void exportGameData() {
        PaperScheduler.of(this).onMain(() -> {
            final GameCatalogue catalogue = GameDataExport.read(getServer(), getLogger()::warning);
            PaperScheduler.of(this).execute(() -> {
                try {
                    GameDataStore.using(pool).publish(getName(), catalogue);
                } catch (final RuntimeException failed) {
                    getLogger().warning("The game data could not be published: " + failed.getMessage());
                }
            });
        });
    }

    /** Opens the pool on the environment's connection and takes every group of this server from the database. */
    private void takeSettings() {
        final Environment environment = Environment.of(settingsPrefix());
        database = setting(
                EnvironmentSettings.of(environment),
                Group.of("database", DatabaseSpec.class).checkedBy(DatabasePool::check));
        pool = DatabasePool.open(database.get(), getName());
        settings = DatabaseSettings.over(SettingStore.using(pool), getName(), environment, logger());
        colourSettings = setting(Colours.GROUP.whileRunning());
        colours = ToneColours.parse(Colours.declared(colourSettings.get()), getLogger()::warning);
        distanceSettings = setting(Distances.group(distanceDefaults()));
        players = setting(NetworkSettings.PLAYERS);
        prestigeSettings = setting(NetworkSettings.PRESTIGE);
        prestige = NetworkSettings.prestige(prestigeSettings.get());
        // Both are read at start only: the season changes with an installation, bundles and clocks are built once.
        season = setting(NetworkSettings.SEASON).get();
        languageAndTime = setting(NetworkSettings.LANGUAGE_AND_TIME).get();
        clock = NetworkTime.clock(NetworkSettings.zone(languageAndTime));
        prepare();
    }

    /** Returns the distances as last taken, within what Paper accepts. */
    private Distances wantedDistances() {
        return Distances.of(distanceSettings.get(), getLogger()::warning);
    }

    /** Opens this process's one signal hub on the database settings; nothing listens until it starts. */
    private SignalHub openHub() {
        final DatabaseSpec login = database.get();
        return SignalHub.open(
                login.jdbcUrl(),
                login.username(),
                login.password(),
                login.queryTimeoutSeconds(),
                getName() + "-signals",
                logger());
    }

    /**
     * Registers this plugin's command root with whatever {@link #commands} adds below it; an empty root stays out.
     *
     * The event is also where Paper hands out the dispatcher, whose whole tree {@link CommandTrees} publishes.
     */
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            commandTrees.take(event.registrar());
            final LiteralArgumentBuilder<CommandSourceStack> root =
                    Commands.literal(commandRoot()).requires(source -> playersUseCommandRoot() || isConsole(source));
            commands(root);
            if (!root.getArguments().isEmpty()) {
                event.registrar().register(root.build());
            }
        });
    }

    private Messages loadMessages() {
        final List<String> roots = new ArrayList<>(List.of("messages/database", "messages/paper-common"));
        roots.addAll(bundles());
        return Messages.load(
                        getClass().getClassLoader(),
                        roots,
                        NetworkSettings.languages(languageAndTime).locales())
                .within(NetworkSettings.environment(
                        getName(), season, languageAndTime, tone -> colours().hex(tone)));
    }

    /** Hides what a non-admin may not type here; the proxy refuses it either way. */
    private void filterCommands(final AdminWatch admins) {
        listen(new CommandFilter(
                NetworkSettings.allowlist(players.get()),
                admins::isAdmin,
                identities,
                renderer,
                this::colours,
                chime()));
    }

    /** Returns the network's limit and allowlist as of their last reload. */
    public final PlayersSpec players() {
        return players.get();
    }

    /** Returns the network's prestige group as of its last reload, for a server that paints names in its colours. */
    public final PrestigeSpec prestigeSettings() {
        return prestigeSettings.get();
    }

    /** Returns the crest table as of the last reload. */
    public final Prestige prestige() {
        return prestige;
    }

    @Override
    public final void onDisable() {
        // The readiness marker stays, going stale is the signal; the server calls off the beat with every other task.
        if (hud != null) {
            // First, so no frame draws over what the plugin takes down.
            quietly("hud.stop", hud::stop);
        }
        if (menus != null) {
            // Before disable: Paper disconnects players after the plugins stop, and a menu may still owe them.
            quietly("menus.stopAll", menus::stopAll);
        }
        quietly("disable", this::disable);
        // Before the pool: a refresh in flight reads through it.
        if (adminWatch != null) {
            quietly("adminWatch.close", adminWatch::close);
        }
        final SignalHub signals = hub;
        if (signals != null) {
            quietly("hub.close", signals::close);
        }
        if (pool != null) {
            quietly("pool.close", pool::close);
        }
        getLogger().info(getName() + " disabled");
    }

    /**
     * Re-reads the colours, the distances, the network's players and this plugin's settings, each alone.
     *
     * @return what could not be re-read, empty when everything was taken
     */
    public final List<String> reload() {
        final List<String> problems = new ArrayList<>();
        try {
            colourSettings.reload();
            colours = ToneColours.parse(Colours.declared(colourSettings.get()), getLogger()::warning);
        } catch (final SettingsException failure) {
            problems.add("the colours: " + failure.getMessage());
        }
        try {
            distanceSettings.reload();
            worldDistances.want(wantedDistances());
            PaperScheduler.of(this)
                    .onMain(() -> worldDistances.apply(getServer().getWorlds()));
        } catch (final SettingsException failure) {
            problems.add("the distances: " + failure.getMessage());
        }
        try {
            players.reload();
        } catch (final SettingsException failure) {
            problems.add("the network's players: " + failure.getMessage());
        }
        try {
            prestigeSettings.reload();
            prestige = NetworkSettings.prestige(prestigeSettings.get());
        } catch (final SettingsException failure) {
            problems.add("the prestige tiers: " + failure.getMessage());
        }
        problems.addAll(reloadOwn());
        problems.forEach(problem -> getLogger().severe("not reloaded, the running values stay: " + problem));
        return List.copyOf(problems);
    }

    /**
     * Answers this plugin's inbox on the hub; call it from {@link #enable()}.
     *
     * The action runs on the hub's thread, never the main one, and a request left running by a crash is failed.
     */
    protected final <P> void answer(final InboxTable<P> table, final Function<P, Answer> action) {
        Inbox.takeOver(pool, table, getName())
                .listen(hub(), request -> outcome(safely(() -> action.apply(request.payload()))));
    }

    /** Shows an admin's player a text they are trying; every plugin's inbox answers its preview with it. */
    protected final Answer preview(final PlayerId player, final MessagePreview preview) {
        return previews.show(player, preview);
    }

    /** Returns a subcommand only the console reaches. */
    protected static LiteralArgumentBuilder<CommandSourceStack> console(final String literal) {
        return Commands.literal(literal).requires(NordtalPlugin::isConsole);
    }

    /** Returns whether a command was typed on the console. */
    protected static boolean isConsole(final CommandSourceStack source) {
        return source.getSender() instanceof ConsoleCommandSender;
    }

    /** Runs an admin action off the main thread and tells whoever typed it what came of it. */
    protected final int run(final CommandContext<CommandSourceStack> context, final Supplier<Answer> action) {
        final CommandSender sender = context.getSource().getSender();
        PaperScheduler.of(this).execute(() -> tell(sender, safely(action)));
        return Command.SINGLE_SUCCESS;
    }

    /** Asks whoever typed an irreversible action to type it again with {@code confirm}. */
    protected final int confirmFirst(final CommandContext<CommandSourceStack> context) {
        tell(
                context.getSource().getSender(),
                Answer.refused(new eu.nordtal.season.messages.Refusal(
                        Confirmation.NEEDED, MESSAGES.admin().confirm("/" + context.getInput()))));
        return Command.SINGLE_SUCCESS;
    }

    /** Tells the console or a player an answer, in English with its tone. */
    protected final void tell(final CommandSender sender, final Answer answer) {
        final PaperUser user = PaperUser.console(this, sender, renderer, this::colours);
        switch (answer) {
            case Answer.Done done -> user.reply(done.message(), Tone.GOOD);
            case Answer.Refused refused -> user.reply(refused.refusal().message(), Tone.WARN);
            case Answer.Failed failed -> user.reply(failed.message(), Tone.BAD);
        }
    }

    /** Returns what an answer becomes in an inbox: English plain text, or the refusal itself. */
    protected final Outcome outcome(final Answer answer) {
        return switch (answer) {
            case Answer.Done done -> Outcome.done(english(done.message()));
            case Answer.Refused refused -> Outcome.refused(refused.refusal());
            case Answer.Failed failed -> Outcome.failed(english(failed.message()));
        };
    }

    private Answer safely(final Supplier<Answer> action) {
        try {
            return action.get();
        } catch (final RuntimeException failure) {
            getLogger().log(Level.WARNING, "an admin action failed", failure);
            return Answer.failed(MESSAGES.admin().failed());
        }
    }

    private String english(final MessageRef message) {
        return PlainTextComponentSerializer.plainText()
                .serialize(renderer.bare().format(Locale.ENGLISH, message));
    }

    /** Why the console was asked to type an action again. */
    private enum Confirmation implements eu.nordtal.season.messages.RefusalReason {
        NEEDED
    }

    /**
     * Loads one group of this plugin's settings, or stops the server naming the group.
     *
     * @return the group, as {@link Settings#load} reads it
     */
    protected final <T> Setting<T> setting(final Group<T> group) {
        return setting(settings, group);
    }

    private <T> Setting<T> setting(final Settings source, final Group<T> group) {
        try {
            return source.load(group);
        } catch (final SettingsException refused) {
            throw fatal(getName() + " is not starting because its settings could not be read: " + refused.getMessage());
        }
    }

    /**
     * Logs why and stops the server; throw what it returns, so nothing after it runs.
     *
     * {@code disablePlugin} goes first, so an ignored shutdown still leaves the plugin off rather than half enabled.
     */
    protected final RuntimeException fatal(final String message) {
        getLogger().severe(message);
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
        return new Fatal();
    }

    /** Runs one disable step so that its failure is logged and the next step still runs. */
    protected final void quietly(final String what, final Runnable step) {
        Shutdown.quietly(what, step, (message, failure) -> getLogger().log(Level.WARNING, message, failure));
    }

    /** Registers a listener for this plugin. */
    protected final void listen(final Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    /**
     * Draws every name this plugin renders with {@code composition} from now on, with its card on hover.
     * A server that composes names calls it from {@link #enable()}; until then, and without it, a name is bare.
     */
    protected final void composeNames(final Names composition) {
        names = Objects.requireNonNull(composition, "composition");
    }

    /**
     * Builds and registers the five lines players read about each other (said, joined, left, died, earned).
     * A server where players see each other calls it once from {@link #enable()}.
     */
    public final SystemLines systemLines() {
        final SystemLines lines = new SystemLines(renderer, identities);
        listen(lines);
        return lines;
    }

    /** Returns the one clock of this process. */
    public final Clock clock() {
        return clock;
    }

    /** Returns the tone palette replies are painted with, as last read. */
    public final ToneColours colours() {
        return colours;
    }

    /**
     * Returns the one renderer of this plugin, over {@code paper-common}'s bundles and its own.
     * It draws a name as {@link #composeNames} set and shows the card of a player held here.
     */
    public final MessageRenderer renderer() {
        return renderer;
    }

    /** Returns the process's one connection pool. */
    public final HikariDataSource pool() {
        return pool;
    }

    /** Returns the process's JDBI over {@link #pool()}. */
    public final Jdbi jdbi() {
        return jdbi;
    }

    /** Returns the access reader over {@link #pool()}. */
    public final AccessReader access() {
        return access;
    }

    /** Returns who everybody online is. */
    public final Identities identities() {
        return identities;
    }

    /** Returns this plugin's heads-up display, where its boss bar lines are declared in {@code enable()}. */
    public final Hud hud() {
        return hud;
    }

    /** Returns the admin roster as last read. */
    public final AdminWatch adminWatch() {
        return adminWatch;
    }

    /** Returns the process's one {@code LISTEN} connection, started once {@link #enable()} returns. */
    public final SignalHub hub() {
        final SignalHub signals = hub;
        if (signals == null) {
            throw new IllegalStateException("the hub exists from enable() on");
        }
        return signals;
    }

    /** Returns this plugin's slf4j logger, which jcore and the database module log through. */
    public final Logger logger() {
        return LoggerFactory.getLogger(getClass());
    }

    /** Thrown only through {@link #fatal}, so nothing after {@code throw fatal(...)} runs. */
    private static final class Fatal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private Fatal() {
            super(null, null, false, false);
        }
    }
}
