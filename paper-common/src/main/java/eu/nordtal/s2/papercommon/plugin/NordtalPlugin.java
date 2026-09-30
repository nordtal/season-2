package eu.nordtal.s2.papercommon.plugin;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.health.Shutdown;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.access.AdminOperators;
import eu.nordtal.s2.database.command.AllowlistDirectory;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.messagerendering.ToneColours;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.papercommon.command.CommandFilter;
import eu.nordtal.s2.papercommon.command.PaperUser;
import eu.nordtal.s2.papercommon.player.Identities;
import eu.nordtal.s2.papercommon.player.Presence;
import eu.nordtal.s2.settings.Check;
import eu.nordtal.s2.settings.Colours;
import eu.nordtal.s2.settings.ColoursSpec;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.Settings;
import eu.nordtal.s2.settings.SettingsException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one start and stop every Nordtal Paper plugin shares; a plugin contributes its settings, bundles and features.
 * A failed start stops the server, since a healthy container without its season does damage nobody sees.
 */
public abstract class NordtalPlugin extends JavaPlugin {

    private final Clock clock = NetworkTime.clock();

    private Settings settings;
    private Setting<DatabaseSpec> database;
    private Setting<ColoursSpec> colourSettings;
    private volatile ToneColours colours;
    private Messages messages;
    private HikariDataSource pool;
    private Jdbi jdbi;
    private AccessReader access;
    private Identities identities;
    private PlayerLocales locales;
    private AdminWatch adminWatch;
    private CommandFilter commandFilter;
    private @Nullable SignalHub hub;
    private @Nullable BukkitTask heartbeat;

    /** Returns the prefix of every environment override of this plugin's settings, {@code NORDTAL_SMP} say. */
    protected abstract String settingsPrefix();

    /** Returns this plugin's message bundle roots, most general first, loaded above {@code paper-common}'s. */
    protected abstract List<String> bundles();

    /** Loads this plugin's own settings and refuses what must hold before the database opens, worlds say. */
    protected abstract void prepare();

    /** Wires this plugin's features onto the running base; refreshes registered on {@link #hub()} run in order. */
    protected abstract void enable();

    /** Stops this plugin's features before the base closes the hub and the pool; wrap each step in quietly. */
    protected void disable() {}

    /** Returns whether a login whose identity cannot be read is refused rather than let in as nobody. */
    protected boolean refusesWithoutIdentity() {
        return false;
    }

    /** Runs on the main thread once a joined player's language is held, the moment to draw what they read. */
    protected void languageKnown(final Player player) {}

    /** Runs on the main thread when an online player gained or lost the admin flag. */
    protected void adminsChanged() {}

    /** Returns how this plugin sounds a refusal; silent unless it has sounds. */
    protected PaperUser.Chime chime() {
        return PaperUser.Chime.silent();
    }

    /** Re-reads this plugin's own settings for a reload, after the base re-read the bundles and the colours. */
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
        settings = FileSettings.in(getDataFolder().toPath(), settingsPrefix(), logger());
        database = setting("database", DatabaseSpec.class, DatabasePool::check);
        colourSettings = setting("colours", ColoursSpec.class, Check.none());
        colours = ToneColours.parse(Colours.declared(colourSettings.get()), getLogger()::warning);
        prepare();

        messages = loadMessages();

        pool = DatabasePool.open(database.get(), getName());
        jdbi = Jdbis.over(pool);
        access = AccessReader.using(pool, clock);
        identities = new Identities(access::identity);
        locales = new PlayerLocales(id -> identities.of(PlayerId.of(id)).locale());

        // ops.json survives a crash, so an admin left in it is swept before any join is handled.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();
        listen(new Presence(
                this,
                identities,
                locales,
                operators,
                messages,
                refusesWithoutIdentity(),
                this::languageKnown,
                logger()));
        adminWatch = new AdminWatch(
                this,
                access,
                operators,
                admins -> {
                    if (identities.recordAdmins(admins)) {
                        adminsChanged();
                    }
                },
                logger());
        commandFilter = filterCommands(adminWatch);

        final DatabaseSpec login = database.get();
        final SignalHub signals = SignalHub.open(
                login.jdbcUrl(),
                login.username(),
                login.password(),
                login.queryTimeoutSeconds(),
                getName() + "-signals",
                logger());
        hub = signals;
        enable();
        adminWatch.listen(signals);
        commandFilter.listen(signals);
        signals.start();

        // Last, so a marker means every step above ran; async, so a frozen main thread lets it go stale.
        final Readiness readiness = Readiness.onDefaultPath(clock, getLogger()::warning);
        heartbeat = getServer()
                .getScheduler()
                .runTaskTimerAsynchronously(this, readiness::refresh, 0L, Readiness.BEAT.toSeconds() * 20L);
        getLogger().info(getName() + " enabled");
    }

    private Messages loadMessages() {
        final List<String> roots = new ArrayList<>(List.of("messages/paper-common"));
        roots.addAll(bundles());
        final Messages loaded = Messages.load(
                        getClass().getClassLoader(),
                        roots,
                        getDataFolder().toPath().resolve("messages"),
                        Languages.NETWORK.locales())
                .within(MessageEnvironment.of(getName()));
        reportUnknownOverrides(loaded);
        return loaded;
    }

    /** What a non-admin may type here; fails open until an allowlist is published, since the proxy enforces it. */
    private CommandFilter filterCommands(final AdminWatch admins) {
        final CommandFilter filter = new CommandFilter(
                CommandFilter.Source.of(AllowlistDirectory.using(pool)),
                admins::isAdmin,
                locales,
                messages,
                logger(),
                this::colours,
                chime());
        listen(filter);
        return filter;
    }

    @Override
    public final void onDisable() {
        // The readiness marker stays: going stale is the signal.
        final BukkitTask beat = heartbeat;
        if (beat != null) {
            quietly("heartbeat.cancel", beat::cancel);
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
     * Re-reads the bundles, the colours and this plugin's own settings, each independently of the others.
     *
     * @return what could not be re-read, empty when everything was taken
     */
    public final List<String> reload() {
        final List<String> problems = new ArrayList<>();
        try {
            messages.reload();
            reportUnknownOverrides(messages);
        } catch (final RuntimeException failure) {
            problems.add("the messages: " + failure.getMessage());
        }
        try {
            colourSettings.reload();
            colours = ToneColours.parse(Colours.declared(colourSettings.get()), getLogger()::warning);
        } catch (final SettingsException failure) {
            problems.add("the colours: " + failure.getMessage());
        }
        problems.addAll(reloadOwn());
        problems.forEach(problem -> getLogger().severe("not reloaded, the running values stay: " + problem));
        return List.copyOf(problems);
    }

    /**
     * Loads one group of this plugin's settings, or stops the server naming the file.
     *
     * @return the group, as {@link Settings#load} reads it
     */
    protected final <T> Setting<T> setting(final String name, final Class<T> spec, final Check<T> check) {
        try {
            return settings.load(name, spec, check);
        } catch (final SettingsException refused) {
            throw fatal(getName() + " is not starting because its configuration could not be read: "
                    + refused.getMessage());
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

    /** Returns the one clock of this process. */
    public final Clock clock() {
        return clock;
    }

    /** Returns the tone palette replies are painted with, as last read. */
    public final ToneColours colours() {
        return colours;
    }

    /** Returns every message this plugin renders: {@code paper-common}'s and its own bundles. */
    public final Messages messages() {
        return messages;
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

    /** Returns every online player's language. */
    public final PlayerLocales locales() {
        return locales;
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

    private void reportUnknownOverrides(final Messages bundles) {
        bundles.unknownOverrideKeys()
                .forEach(key -> getLogger()
                        .warning("the message override names " + key
                                + ", which no bundle declares: it is stored and never used; check the spelling"));
    }

    /** Thrown only through {@link #fatal}, so nothing after {@code throw fatal(...)} runs. */
    private static final class Fatal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private Fatal() {
            super(null, null, false, false);
        }
    }
}
