package eu.nordtal.s2.steward;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.ProcessScheduler;
import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.online.OnlineDirectory;
import eu.nordtal.s2.database.payment.Tiers;
import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.settings.DatabaseSettings;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.DatabaseWaiting;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.settings.network.LanguageAndTimeSpec;
import eu.nordtal.s2.settings.network.NetworkSettings;
import eu.nordtal.s2.steward.alert.Thresholds;
import eu.nordtal.s2.steward.api.PluginsForward;
import eu.nordtal.s2.steward.api.StackApi;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.backup.Schedules;
import eu.nordtal.s2.steward.bunq.PaymentLoop;
import eu.nordtal.s2.steward.config.AlertsSpec;
import eu.nordtal.s2.steward.config.StewardSettings;
import eu.nordtal.s2.steward.config.StewardSpec;
import eu.nordtal.s2.steward.config.WebSpec;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.metric.MetricRecorder;
import eu.nordtal.s2.steward.web.Web;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Entry point: {@code serve}, {@code forget-factors} and {@code generate-vapid-keys}.
 *
 * Steward is the interface; every run, the schema and every file belong to steward-agent.
 */
@Slf4j
public final class Steward {

    /** The one clock of this process. */
    private static final Clock CLOCK = NetworkTime.clock();

    /** The logger the settings name what they refused or ignored in. */
    private static final org.slf4j.Logger SETTINGS_LOG = LoggerFactory.getLogger(StewardSettings.class);

    /** The payment loop, the scheduled clocks and the web interface, for the container's lifetime. */
    private static final String SERVE = "serve";

    /** Clears a signed-in admin's second factor. Reachable only from a shell on the host. */
    private static final String FORGET = "forget-factors";

    private Steward() {}

    public static void main(final String[] args) {
        final int status = switch (command(args)) {
            case FORGET -> ForgetFactors.run(args, databaseConfig(), CLOCK);
            case Web.GENERATE_VAPID_KEYS -> generateVapidKeys();
            case SERVE, "" -> serve();
            // Refused rather than served: a second interface beside the running one is never what a typo meant.
            default -> {
                log.error(
                        "`{}` is not one of steward's commands: serve, {} and {}. A run is asked for in the"
                                + " interface or with `steward-agent request`.",
                        command(args),
                        FORGET,
                        Web.GENERATE_VAPID_KEYS);
                yield 2;
            }
        };
        System.exit(status);
    }

    private static int serve() {
        final DatabaseSpec databaseConfig = databaseConfig();
        if (databaseConfig == null) {
            return 1;
        }
        final Database opened = DatabaseWaiting.openDatabase(databaseConfig, SERVE, Waiting.on(CLOCK));
        if (opened == null) {
            return 1;
        }
        // Closed before the database, so no timed work outlives the pool it reads through.
        try (Database database = opened;
                ProcessScheduler scheduler =
                        new ProcessScheduler("steward", failure -> log.error("A task of Steward failed", failure))) {
            final Configs configs = configsOf(databaseConfig, database);
            if (configs == null) {
                return 1;
            }
            markReady(scheduler);
            return serveNetwork(configs, database, scheduler);
        }
    }

    /**
     * The settings {@code serve} runs on.
     *
     * @param handle the steward group, so a change in Steward re-arms the two clocks
     * @param config what {@code handle} hands out, which reads through to every reload
     * @param settings where both groups came from, listened to for a change
     * @param languages the network's at this start, which the announcements are listed in
     * @param tiers the network's price list at this start, which payments are booked by
     */
    private record Configs(
            Setting<StewardSpec> handle,
            StewardSpec config,
            WebSpec web,
            Setting<AlertsSpec> alerts,
            DatabaseSpec database,
            DatabaseSettings settings,
            ZoneId zone,
            Languages languages,
            Tiers tiers) {}

    /** Takes both groups out of the database, or {@code null}. */
    private static @Nullable Configs configsOf(final DatabaseSpec databaseConfig, final Database database) {
        final DatabaseSettings settings = StewardSettings.stored(database.dataSource(), SETTINGS_LOG);
        try {
            final Setting<StewardSpec> handle = StewardSettings.steward(settings);
            final WebSpec web = StewardSettings.web(settings).get();
            final Setting<AlertsSpec> alerts = StewardSettings.alerts(settings);
            // Steward starts first, so the network's groups are published before any server asks for them.
            settings.load(NetworkSettings.PLAYERS);
            settings.load(NetworkSettings.SEASON);
            final Tiers tiers =
                    NetworkSettings.tiers(settings.load(NetworkSettings.PRICES).get());
            final LanguageAndTimeSpec languageAndTime =
                    settings.load(NetworkSettings.LANGUAGE_AND_TIME).get();
            return new Configs(
                    handle,
                    handle.get(),
                    web,
                    alerts,
                    databaseConfig,
                    settings,
                    NetworkSettings.zone(languageAndTime),
                    NetworkSettings.languages(languageAndTime),
                    tiers);
        } catch (final SettingsException broken) {
            // No stack trace, so the sentence is not missed.
            log.error("Refusing to serve on settings that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    private static int serveNetwork(final Configs configs, final Database database, final Scheduler scheduler) {
        final StewardSpec config = configs.config();
        final AgentClient agent = agentOf(config);
        if (config.agent().token().isBlank()) {
            // Said once: every container route, every run and the curves need the agent.
            log.warn("agent.token is empty in the steward group, so this container cannot ask"
                    + " steward-agent for anything: no container page, no log and no plugin list."
                    + " The setup script writes that secret.");
        }
        try (MetricRecorder recorder =
                new MetricRecorder(agent::samples, MetricDirectory.using(database.dataSource()), CLOCK)) {
            recorder.start(scheduler);
            return serveWithAgent(configs, database, agent, scheduler);
        }
    }

    /** The one client of steward-agent, for the curves and the web alike. */
    private static AgentClient agentOf(final StewardSpec config) {
        return new AgentClient(new InternalClient(
                AgentWire.SERVICE,
                config.agent().url(),
                config.agent().token(),
                Duration.ofSeconds(config.httpTimeoutSeconds())));
    }

    private static int serveWithAgent(
            final Configs configs, final Database database, final AgentClient agent, final Scheduler scheduler) {
        final StewardSpec config = configs.config();
        // The schedules and the season dates tell time in the network's zone.
        final Clock zoned = NetworkTime.clock(configs.zone());
        // One set of directories, shared by the schedules, the stack routes and the web.
        final Data data = new Data(database, zoned);

        try (Schedules schedules = new Schedules(data.updates(), config, zoned, scheduler);
                StackApi stack = buildStack(config, agent, database, data, configs.zone(), scheduler)) {
            // Started after the marker, so a failure of the interface cannot keep the servers down.
            final Web web = startWeb(configs, stack, agent, data, scheduler);
            try {
                // The nightly backup and the optional scheduled update.
                schedules.arm();
                return serveWithApi(configs, database, scheduler, () -> reReadOwn(configs, schedules), web::listen);
            } finally {
                web.stop();
            }
        }
    }

    /** Builds and starts the web interface on the {@code web} group's port, with the stack routes on it. */
    private static Web startWeb(
            final Configs configs,
            final StackApi stack,
            final AgentClient agent,
            final Data data,
            final Scheduler scheduler) {
        final WebSpec webConfig = configs.web();
        final StewardSpec config = configs.config();
        if (webConfig.webPush().publicKey().isBlank()) {
            log.warn("web-push has no VAPID keypair yet, so the alerts cannot reach a phone's"
                    + " lock screen. Run `steward " + Web.GENERATE_VAPID_KEYS + "` and paste both"
                    + " lines it prints into the web group's web-push section.");
        }
        final AlertsSpec alerts = configs.alerts().get();
        final Web web = new Web(
                webConfig,
                () -> new Thresholds(alerts.diskPercent(), alerts.memoryPercent(), alerts.backupAgeHours()),
                new DiscordAuth(webConfig.discord(), webConfig.publicUrl()),
                stack,
                agent,
                !config.agent().token().isBlank(),
                data,
                configs.languages(),
                CLOCK,
                scheduler);
        web.start(webConfig.port());
        stack.warm();
        return web;
    }

    /** Builds the stack routes the web serves: services, logs, the console, files, the host and plugins. */
    private static StackApi buildStack(
            final StewardSpec config,
            final AgentClient agent,
            final Database database,
            final Data data,
            final ZoneId zone,
            final Scheduler scheduler) {
        // Every process's settings, which its signal re-reads; the proxy's pack among them.
        final SettingStore settings = SettingStore.using(database.dataSource());
        return new StackApi(
                agent,
                data.updates(),
                data.audit(),
                () -> new StackApi.Nightly(
                        config.backup().at(),
                        config.backup().days(),
                        config.update().at(),
                        config.update().days(),
                        zone),
                // The player counts proxy writes, and the player list next to them.
                new eu.nordtal.s2.steward.api.ServicesApi(
                        OnlineDirectory.using(database.dataSource(), CLOCK), data.roster(), CLOCK),
                // The agent's resolve, so the page can ask what is newest without a run.
                agent::plan,
                // The agent's plugin list and search; removing one is a run.
                new PluginsForward(agent, data.updates()),
                // The overrides every process re-reads on the signal a save sends.
                eu.nordtal.s2.database.message.MessageOverrideStore.using(database.dataSource()),
                settings,
                CLOCK,
                scheduler);
    }

    /** Takes the live groups again after a change in Steward and re-arms the two clocks from the steward group. */
    private static void reReadOwn(final Configs configs, final Schedules schedules) {
        try {
            // Read at the alert monitor's next reading, through the one instance the group hands out.
            configs.alerts().reload();
        } catch (final SettingsException broken) {
            log.warn("The alert thresholds were changed but refused, so the last ones stay: {}", broken.getMessage());
        }
        try {
            configs.handle().reload();
        } catch (final SettingsException broken) {
            // The problem is on the group, where Steward shows it; the values in use stay.
            log.warn("The steward settings were changed but refused, so the last ones stay: {}", broken.getMessage());
            return;
        }
        schedules.arm();
    }

    /** The payment loop over steward-bunq and the signals the web and the settings listen on, until SIGTERM. */
    private static int serveWithApi(
            final Configs configs,
            final Database database,
            final Scheduler scheduler,
            final Runnable onSettings,
            final java.util.function.Consumer<SignalHub> alerts) {
        final StewardSpec config = configs.config();
        final DatabaseSpec databaseConfig = configs.database();
        try (PaymentLoop paymentLoop = PaymentsStartup.start(
                        config,
                        configs.tiers(),
                        database,
                        Waiting.on(CLOCK),
                        Duration.ofSeconds(config.httpTimeoutSeconds()),
                        scheduler);
                SignalHub signals = SignalHub.open(
                        databaseConfig.jdbcUrl(),
                        databaseConfig.username(),
                        databaseConfig.password(),
                        databaseConfig.queryTimeoutSeconds(),
                        "steward-signals",
                        log)) {
            configs.settings().listen(signals, onSettings);
            if (paymentLoop != null) {
                paymentLoop.listen(signals);
            }
            alerts.accept(signals);
            signals.start();
            // SIGTERM is how a restart asks; the resources above close on the way out.
            final CountDownLatch stopping = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stopping::countDown, "steward-shutdown"));
            stopping.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** Writes the readiness marker once the schema is current and keeps it fresh; every service waits for it. */
    private static void markReady(final Scheduler scheduler) {
        final Readiness readiness = Readiness.onDefaultPath(CLOCK, log::warn);
        if (!readiness.keepBeating(scheduler)) {
            // Fatal to everything waiting on this process, so it is loud.
            log.error(
                    "Could not write the readiness marker {}. The rest of the stack will not"
                            + " start, because its healthcheck reads this file.",
                    readiness.marker());
        }
    }

    /**
     * The subcommand, or the empty string.
     *
     * Anything unrecognised reads as the default, which cannot break anything.
     */
    private static String command(final String[] args) {
        return args.length == 0 ? "" : args[0].strip().toLowerCase(Locale.ROOT);
    }

    private static @Nullable DatabaseSpec databaseConfig() {
        try {
            return StewardSettings.database().get();
        } catch (final SettingsException broken) {
            log.error("Refusing to touch the database on settings that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    /** Prints a fresh VAPID keypair for the {@code web} group's web-push section, public half first. */
    private static int generateVapidKeys() {
        final com.interaso.webpush.VapidKeys keys = com.interaso.webpush.VapidKeys.generate();
        System.out.println(keys.getX509PublicKey());
        System.out.println(keys.getPkcs8PrivateKey());
        return 0;
    }
}
