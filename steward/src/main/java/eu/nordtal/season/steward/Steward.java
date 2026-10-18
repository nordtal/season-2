package eu.nordtal.season.steward;

import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.ProcessScheduler;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.metric.MetricDirectory;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.internalapi.agent.AgentWire;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.DatabaseWaiting;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.LanguageAndTimeSpec;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.backup.Schedules;
import eu.nordtal.season.steward.bunq.PaymentLoop;
import eu.nordtal.season.steward.config.AlertsSpec;
import eu.nordtal.season.steward.config.StewardSettings;
import eu.nordtal.season.steward.config.StewardSpec;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.metric.MetricRecorder;
import eu.nordtal.season.steward.stack.PluginsForward;
import eu.nordtal.season.steward.stack.StackApi;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/** Runs the subcommands {@code serve}, {@code forget-factors} and {@code generate-vapid-keys}. */
@Slf4j
public final class Steward {

    private static final Clock CLOCK = NetworkTime.clock();

    private static final org.slf4j.Logger SETTINGS_LOG = LoggerFactory.getLogger(StewardSettings.class);

    /** The subcommand that starts the payment loop, the scheduled clocks and the web interface. */
    private static final String SERVE = "serve";

    private static final String FORGET = "forget-factors";

    private Steward() {}

    public static void main(final String[] args) {
        final int status = switch (command(args)) {
            case FORGET -> ForgetFactors.run(args, databaseConfig(), CLOCK);
            case Web.GENERATE_VAPID_KEYS -> generateVapidKeys();
            case SERVE, "" -> serve();
            // An unknown subcommand must not serve.
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
        // The scheduler closes before the database, so no timed work reads a closed pool.
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
     * @param handle the steward group, which rearms the two clocks when it changes
     * @param config the values of {@code handle}, read again on every reload
     * @param settings the source of both groups, listened to for changes
     * @param languages the network's languages at start, which list the announcements
     * @param tiers the network's price list at start, which payments are booked by
     */
    record Configs(
            Setting<StewardSpec> handle,
            StewardSpec config,
            WebSpec web,
            Setting<AlertsSpec> alerts,
            DatabaseSpec database,
            DatabaseSettings settings,
            ZoneId zone,
            Languages languages,
            Tiers tiers) {}

    /** Reads both groups from the database, or returns {@code null}. */
    static @Nullable Configs configsOf(final DatabaseSpec databaseConfig, final Database database) {
        final DatabaseSettings settings = StewardSettings.stored(database.dataSource(), SETTINGS_LOG);
        try {
            final Setting<StewardSpec> handle = StewardSettings.steward(settings);
            final WebSpec web = StewardSettings.web(settings).get();
            final Setting<AlertsSpec> alerts = StewardSettings.alerts(settings);
            // Publishes the network's groups before any server asks for them.
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
            log.error("Refusing to serve on settings that cannot be read: {}", broken.getMessage());
            return null;
        }
    }

    private static int serveNetwork(final Configs configs, final Database database, final Scheduler scheduler) {
        final StewardSpec config = configs.config();
        final AgentClient agent = agentOf(config);
        if (config.agent().token().isBlank()) {
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

    /** Returns the client of steward-agent that the curves and the web share. */
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
        final Clock zoned = NetworkTime.clock(configs.zone());
        final Data data = new Data(database, zoned);

        try (Schedules schedules = new Schedules(data.updates(), config, zoned, scheduler);
                StackApi stack = buildStack(config, agent, database, data, configs.zone(), scheduler)) {
            // Starts after the readiness marker, so an interface failure cannot keep the servers down.
            final Web web = startWeb(configs, stack, agent, data, scheduler);
            try {
                schedules.arm();
                return serveWithApi(configs, database, scheduler, () -> reReadOwn(configs, schedules), web::listen);
            } finally {
                web.stop();
            }
        }
    }

    /** Starts the web interface and the stack routes on the port of the {@code web} group. */
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

    /** Builds the stack routes the web serves. */
    static StackApi buildStack(
            final StewardSpec config,
            final AgentClient agent,
            final Database database,
            final Data data,
            final ZoneId zone,
            final Scheduler scheduler) {
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
                new eu.nordtal.season.steward.stack.ServicesApi(
                        OnlineDirectory.using(database.dataSource(), CLOCK), data.roster(), CLOCK),
                agent::plan,
                new PluginsForward(agent, data.updates()),
                eu.nordtal.season.database.message.MessageOverrideStore.using(database.dataSource()),
                eu.nordtal.season.database.document.DocumentStore.using(database.dataSource()),
                settings,
                CLOCK,
                scheduler);
    }

    /** Reloads the live groups after a change in Steward and rearms both clocks from the {@code steward} group. */
    private static void reReadOwn(final Configs configs, final Schedules schedules) {
        try {
            configs.alerts().reload();
        } catch (final SettingsException broken) {
            log.warn("The alert thresholds were changed but refused, so the last ones stay: {}", broken.getMessage());
        }
        try {
            configs.handle().reload();
        } catch (final SettingsException broken) {
            log.warn("The steward settings were changed but refused, so the last ones stay: {}", broken.getMessage());
            return;
        }
        schedules.arm();
    }

    /** Runs the payment loop over steward-bunq and the signals the web and the settings listen on until SIGTERM. */
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
            final CountDownLatch stopping = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(stopping::countDown, "steward-shutdown"));
            stopping.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    /** Writes the readiness marker once the schema is current and keeps it fresh. Every service waits for it. */
    private static void markReady(final Scheduler scheduler) {
        final Readiness readiness = Readiness.onDefaultPath(CLOCK, log::warn);
        if (!readiness.keepBeating(scheduler)) {
            log.error(
                    "Could not write the readiness marker {}. The rest of the stack will not"
                            + " start, because its healthcheck reads this file.",
                    readiness.marker());
        }
    }

    /** Returns the subcommand, or the empty string. */
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

    /** Prints a new VAPID keypair for the web-push section of the {@code web} group, public half first. */
    private static int generateVapidKeys() {
        final com.interaso.webpush.VapidKeys keys = com.interaso.webpush.VapidKeys.generate();
        System.out.println(keys.getX509PublicKey());
        System.out.println(keys.getPkcs8PrivateKey());
        return 0;
    }
}
