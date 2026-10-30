package eu.nordtal.season.papercommon.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.health.Shutdown;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.access.AccessReader;
import eu.nordtal.season.database.access.AdminOperators;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.database.command.CommandTreeStore;
import eu.nordtal.season.database.command.CommandTreeWriter;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.game.GameCatalogue;
import eu.nordtal.season.database.game.GameDataStore;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxTable;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.mode.ModeStateStore;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.Names;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.papercommon.access.AdminWatch;
import eu.nordtal.season.papercommon.access.BukkitOps;
import eu.nordtal.season.papercommon.chat.Previews;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.CommandFilter;
import eu.nordtal.season.papercommon.command.CommandTrees;
import eu.nordtal.season.papercommon.command.PaperUser;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.game.GameDataExport;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.papercommon.menu.Menus;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.player.InventoryEditor;
import eu.nordtal.season.papercommon.player.ModeStates;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
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

/** The start and stop that every Nordtal Paper plugin shares. A failed start stops the server. */
public abstract class NordtalPlugin extends JavaPlugin {

    private Clock clock = NetworkTime.clock();

    private DatabaseSettings settings;

    /** This plugin's own groups by name, which a {@link #checkSettings} names. */
    private final Map<String, Group<?>> groups = new java.util.concurrent.ConcurrentHashMap<>();

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
    // Read on every name drawn, from any thread.
    private volatile Names names = Names.BARE;
    private MessageRenderer renderer;
    private AppliedDocuments documents;
    private Previews previews;
    private HikariDataSource pool;
    private Jdbi jdbi;
    private AccessReader access;
    private Identities identities;
    private Hud hud;
    private Menus menus;
    private AdminWatch adminWatch;
    private ModeStates modeStates;
    private CommandTrees commandTrees;
    private @Nullable SignalHub hub;

    protected abstract String settingsPrefix();

    /** Returns the roots of this plugin's message bundles, most general first. */
    protected abstract List<String> bundles();

    protected abstract String commandRoot();

    /** Returns whether players reach the command root. If not, only the console does. */
    protected boolean playersUseCommandRoot() {
        return false;
    }

    protected void commands(final LiteralArgumentBuilder<CommandSourceStack> root) {}

    /** Loads this plugin's settings and refuses what must hold before the features start. */
    protected abstract void prepare();

    /** Starts this plugin's features. Refreshes registered on {@link #hub()} run in order. */
    protected abstract void enable();

    /** Stops this plugin's features before the base closes the hub and the pool. Wrap each step in quietly. */
    protected void disable() {}

    /** Returns whether a login without a readable identity is refused. */
    protected boolean refusesWithoutIdentity() {
        return false;
    }

    /** Runs on the main thread one tick after a player joined, after every join handler. */
    protected void languageKnown(final Player player) {}

    protected PaperUser.Chime chime() {
        return PaperUser.Chime.silent();
    }

    /** Returns the distances for worlds where no admin sets one. */
    protected Distances distanceDefaults() {
        return Distances.NONE;
    }

    /** Re-reads this plugin's own settings for a reload and returns what it could not read. */
    protected List<String> reloadOwn() {
        return List.of();
    }

    @Override
    public final void onEnable() {
        Shutdown.warmUp();
        try {
            start();
        } catch (final Fatal fatal) {
            // fatal() already logged the reason.
        } catch (final RuntimeException failure) {
            final RuntimeException _ = fatal(getName() + " is not starting: " + failure.getMessage());
        }
    }

    private void start() {
        takeSettings();

        worldDistances = new WorldDistances(wantedDistances(), logger());
        worldDistances.apply(getServer().getWorlds());
        listen(worldDistances);

        messages = loadMessages();
        documents = new AppliedDocuments(getClass().getClassLoader(), namespaces());

        jdbi = Jdbis.over(pool);
        access = AccessReader.using(pool, clock);
        identities = new Identities(access::identities);
        renderer = MessageRenderer.of(
                        messages, (name, reader) -> names.draw(name, reader), identities.cards(this::prestige))
                .reading(documents);
        previews = newPreviews();
        hud = new Hud(this, identities);
        listen(hud);
        // Declared first, the drafted bar stands above the lines the plugin declares in enable().
        hud.declare(previews::bossBar);
        menus = new Menus(chime());
        listen(menus);

        // ops.json survives a crash, so a join must find no admin left in it.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();
        listen(new Presence(
                this, identities, operators, renderer, refusesWithoutIdentity(), this::languageKnown, logger()));
        adminWatch = new AdminWatch(this, access, operators, logger());
        filterCommands(adminWatch);
        modeStates = new ModeStates(this, ModeStateStore.using(pool), adminWatch::isAdmin, logger());
        listen(modeStates);
        // Only the proxy enforces the network's limit.
        listen(new Unbounded());

        final SignalHub signals = openHub();
        hub = signals;
        commandTrees = new CommandTrees(this, new CommandTreeWriter(CommandTreeStore.using(pool), getName()));
        listen(commandTrees);
        registerCommands();
        enable();
        // The HUD clock starts after enable(), which declares the lines.
        hud.start();
        adminWatch.listen(signals);
        signals.on(Channel.ADMIN, "who the players are", identities::reread);
        settings.listen(signals, this::reload);
        // The hub reads the overrides when it connects; until then the packaged texts show.
        MessageOverrideStore.using(pool).follow(messages, signals);
        // A menu reads its document when it opens, so a change needs nothing redrawn.
        documents.follow(DocumentStore.using(pool), signals, () -> {});
        signals.start();
        exportGameData();

        // The marker is written last, off the main thread, so a frozen server lets it go stale.
        final var _ = Readiness.onDefaultPath(clock, getLogger()::warning).keepBeating(PaperScheduler.of(this));
        getLogger().info(getName() + " enabled");
    }

    /**
     * Writes the game data for Steward's pickers on the first tick, off the main thread.
     * A failed write costs the pickers this server's entries.
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
        season = setting(NetworkSettings.SEASON).get();
        languageAndTime = setting(NetworkSettings.LANGUAGE_AND_TIME).get();
        clock = NetworkTime.clock(NetworkSettings.zone(languageAndTime));
        prepare();
    }

    private Distances wantedDistances() {
        return Distances.of(distanceSettings.get(), getLogger()::warning);
    }

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

    /** Registers the command root with the subcommands of {@link #commands}. An empty root is not registered. */
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

    private List<String> roots() {
        final List<String> roots = new ArrayList<>(List.of("messages/database"));
        roots.addAll(bundles());
        return roots;
    }

    /**
     * Returns the namespaces whose documents are in {@code documents/<namespace>}.
     * They are paper-common's, the plugin's own and each bundle's: a plugin's own documents do not depend on it
     * keeping a bundle.
     */
    private Set<String> namespaces() {
        final Set<String> namespaces = new HashSet<>(Set.of("paper-common", getName()));
        roots().forEach(root -> namespaces.add(root.substring(root.lastIndexOf('/') + 1)));
        return namespaces;
    }

    private Messages loadMessages() {
        final List<String> roots = roots();
        return Messages.load(
                        getClass().getClassLoader(),
                        roots,
                        NetworkSettings.languages(languageAndTime).locales())
                .within(NetworkSettings.environment(
                        getName(), season, languageAndTime, tone -> colours().hex(tone)));
    }

    /** Hides the commands that a non-admin may not type. */
    private void filterCommands(final AdminWatch admins) {
        listen(new CommandFilter(
                NetworkSettings.allowlist(players.get()), admins::isAdmin, identities, renderer, chime()));
    }

    public final PlayersSpec players() {
        return players.get();
    }

    public final PrestigeSpec prestigeSettings() {
        return prestigeSettings.get();
    }

    public final Prestige prestige() {
        return prestige;
    }

    @Override
    public final void onDisable() {
        // The readiness marker stays and goes stale.
        if (hud != null) {
            quietly("hud.stop", hud::stop);
        }
        if (menus != null) {
            // Paper disconnects players after the plugins stop.
            quietly("menus.stopAll", menus::stopAll);
        }
        quietly("disable", this::disable);
        if (modeStates != null) {
            quietly("modeStates.close", modeStates::close);
        }
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
     * Returns what it could not read.
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
     * Answers this plugin's inbox on the hub. Call it in {@link #enable()}. The action runs on the hub's thread.
     * It is handed each request and the actor who asked for it.
     */
    protected final <P> void answer(final InboxTable<P> table, final BiFunction<P, Actor, Answer> action) {
        Inbox.takeOver(pool, table, getName())
                .listen(hub(), request -> outcome(safely(() -> action.apply(request.payload(), request.actor()))));
    }

    protected final Answer preview(final PlayerId player, final MessagePreview preview) {
        return previews.show(player, preview);
    }

    /** A preview is painted in the colours of the key's own service, as its settings name them. */
    private Previews newPreviews() {
        final SettingStore store = SettingStore.using(pool);
        return new Previews(
                getServer()::getPlayer,
                renderer,
                service -> ToneColours.parse(Colours.current(store, service), unreadable -> {}),
                PaperScheduler.of(this).mainThread());
    }

    protected final Answer preview(final PlayerId player, final DocumentPreview preview) {
        return previews.show(player, preview.language(), documents.drafted(preview));
    }

    /** Stores a copy of the state a player here holds, so Steward shows it as it is. */
    protected final Answer storeModeState(final PlayerId player) {
        modeStates.storeHeld(player);
        return Answer.done(Sent.of(Replies.STATE_STORED));
    }

    /** Removes the stack Steward showed, if the slot of the state a player here holds still has it. */
    protected final Answer removeStack(
            final PlayerId player,
            final Mode mode,
            final Container container,
            final int slot,
            final String item,
            final int amount) {
        return modeStates.remove(player, mode, container, slot, item, amount)
                ? Answer.done(Sent.of(Replies.STACK_REMOVED))
                : Answer.refused(ServerRefusal.STACK_CHANGED.with());
    }

    /** Opens an editor of a player's Survival inventory or ender chest for an admin on this server. */
    protected final Answer openInventory(final PlayerId admin, final PlayerId player, final Container container) {
        final Locale locale = identities.languageOf(admin.value());
        final GameDocument document = new GameDocument(
                renderer,
                documents.applied(
                        container == Container.INVENTORY ? InventoryEditor.INVENTORY : InventoryEditor.ENDER_CHEST),
                Viewer.of(locale),
                new DocumentSpec.None());
        final IntConsumer kept = count -> {
            final Player here = getServer().getPlayer(admin.value());
            if (here != null) {
                here.sendMessage(renderer.format(locale, InventoryEditor.KEPT.with(new InventoryEditor.Kept(count))));
            }
        };
        return switch (modeStates.open(
                admin, player, container, MenuTitle.of(InventoryEditor.rows(container), document.title()), kept)) {
            case OPENED -> Answer.done(Sent.of(Replies.EDITOR_OPENED));
            case ADMIN_AWAY -> Answer.refused(ServerRefusal.NOT_HERE.with());
            case NOT_EDITABLE -> Answer.refused(ServerRefusal.NOT_EDITABLE.with());
        };
    }

    protected static LiteralArgumentBuilder<CommandSourceStack> console(final String literal) {
        return Commands.literal(literal).requires(NordtalPlugin::isConsole);
    }

    protected static boolean isConsole(final CommandSourceStack source) {
        return source.getSender() instanceof ConsoleCommandSender;
    }

    /** Runs an admin action off the main thread and tells the sender the result. */
    protected final int run(final CommandContext<CommandSourceStack> context, final Supplier<Answer> action) {
        final CommandSender sender = context.getSource().getSender();
        PaperScheduler.of(this).execute(() -> tell(sender, safely(action)));
        return Command.SINGLE_SUCCESS;
    }

    /** Asks the sender of an irreversible action to type it again with {@code confirm}. */
    protected final int confirmFirst(final CommandContext<CommandSourceStack> context) {
        PaperUser.console(this, context.getSource().getSender(), renderer, this::colours)
                .reply(Replies.CONFIRM.with(new Replies.Typed("/" + context.getInput())));
        return Command.SINGLE_SUCCESS;
    }

    protected final void tell(final CommandSender sender, final Answer answer) {
        final PaperUser user = PaperUser.console(this, sender, renderer, this::colours);
        switch (answer) {
            case Answer.Done done -> user.reply(done.message());
            case Answer.Refused refused -> user.refuse(refused.refusal().message());
            case Answer.Failed failed -> user.reply(failed.message());
        }
    }

    /** Returns the outcome of an answer in an inbox: its status and a refusal. A failure's words go to the log. */
    private Outcome outcome(final Answer answer) {
        return switch (answer) {
            case Answer.Done done -> Outcome.done(null);
            case Answer.Refused refused -> Outcome.refused(refused.refusal());
            case Answer.Failed failed -> {
                getLogger().warning("a request failed: " + english(failed.message()));
                yield Outcome.failed(null);
            }
        };
    }

    private Answer safely(final Supplier<Answer> action) {
        try {
            return action.get();
        } catch (final RuntimeException failure) {
            getLogger().log(Level.WARNING, "an admin action failed", failure);
            return Answer.failed(Sent.of(Replies.FAILED));
        }
    }

    private String english(final Sent<?> message) {
        return PlainTextComponentSerializer.plainText()
                .serialize(renderer.bare().format(Locale.ENGLISH, message));
    }

    /**
     * Answers whether this server would start with {@code change} saved.
     * The group's check runs first, then {@link #refusesAtStart}. A group this plugin does not load refuses nothing.
     */
    protected final Answer checkSettings(final SettingsChange change) {
        final Group<?> group = groups.get(change.group());
        final String refusal;
        try {
            refusal = group == null ? null : trial(group, change);
        } catch (final SettingsException refused) {
            return refusedSettings(String.valueOf(refused.getMessage()));
        }
        return refusal == null ? Answer.done(Sent.of(Replies.SETTINGS_TAKEN)) : refusedSettings(refusal);
    }

    private <T> @Nullable String trial(final Group<T> group, final SettingsChange change) throws SettingsException {
        return refusesAtStart(group, settings.trial(group, change));
    }

    private Answer refusedSettings(final String reason) {
        return Answer.refused(ServerRefusal.SETTINGS_REFUSED.with(getName(), reason));
    }

    /** Returns why this server would not start with {@code value} as {@code group}, or null. Its check has passed. */
    protected <T> @Nullable String refusesAtStart(final Group<T> group, final T value) {
        return null;
    }

    /** Loads one group of this plugin's settings, or stops the server and names the group. */
    protected final <T> Setting<T> setting(final Group<T> group) {
        return setting(settings, group);
    }

    private <T> Setting<T> setting(final Settings source, final Group<T> group) {
        try {
            final Setting<T> loaded = source.load(group);
            if (!group.network()) {
                groups.put(group.name(), group);
            }
            return loaded;
        } catch (final SettingsException refused) {
            throw fatal(getName() + " is not starting because its settings could not be read: " + refused.getMessage());
        }
    }

    /**
     * Logs the reason, writes it to the file {@code NORDTAL_REFUSED_START} names and stops the server.
     * The entrypoint then holds the container, which Docker would restart into the same refusal.
     * Throw the result, so that no code after it runs.
     */
    protected final RuntimeException fatal(final String message) {
        getLogger().severe(message);
        final String refused = System.getenv("NORDTAL_REFUSED_START");
        if (refused != null) {
            try {
                Files.writeString(Path.of(refused), message);
            } catch (final IOException | RuntimeException unwritten) {
                getLogger()
                        .warning("the refusal could not be written to " + refused + ", so the container restarts: "
                                + unwritten.getMessage());
            }
        }
        getServer().getPluginManager().disablePlugin(this);
        getServer().shutdown();
        return new Fatal();
    }

    /** Runs a disable step. A failure is logged, and the next step still runs. */
    protected final void quietly(final String what, final Runnable step) {
        Shutdown.quietly(what, step, (message, failure) -> getLogger().log(Level.WARNING, message, failure));
    }

    protected final void listen(final Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    /**
     * Draws every name of this plugin with {@code composition}.
     * Call it in {@link #enable()}.
     * Until then a name is bare.
     */
    protected final void composeNames(final Names composition) {
        names = Objects.requireNonNull(composition, "composition");
    }

    /**
     * Creates the five lines that players read about each other: said, joined, left, died, earned.
     * Call it once in {@link #enable()}.
     */
    public final SystemLines systemLines() {
        final SystemLines lines = new SystemLines(renderer, identities);
        listen(lines);
        return lines;
    }

    public final Clock clock() {
        return clock;
    }

    public final ToneColours colours() {
        return colours;
    }

    /** Returns the documents of {@code paper-common} and of this plugin, an admin's in place of a packaged one. */
    public final AppliedDocuments documents() {
        return documents;
    }

    /** Returns the renderer over the bundles of {@code paper-common} and of this plugin. */
    public final MessageRenderer renderer() {
        return renderer;
    }

    public final HikariDataSource pool() {
        return pool;
    }

    public final Jdbi jdbi() {
        return jdbi;
    }

    public final AccessReader access() {
        return access;
    }

    public final Identities identities() {
        return identities;
    }

    public final Hud hud() {
        return hud;
    }

    public final AdminWatch adminWatch() {
        return adminWatch;
    }

    /** Returns the process's one {@code LISTEN} connection. It starts when {@link #enable()} returns. */
    public final SignalHub hub() {
        final SignalHub signals = hub;
        if (signals == null) {
            throw new IllegalStateException("the hub exists from enable() on");
        }
        return signals;
    }

    public final Logger logger() {
        return LoggerFactory.getLogger(getClass());
    }

    private static final class Fatal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private Fatal() {
            super(null, null, false, false);
        }
    }
}
