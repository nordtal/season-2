package eu.nordtal.season.discordbot;

import eu.nordtal.season.common.health.Readiness;
import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.common.time.NetworkTime;
import eu.nordtal.season.common.time.ProcessScheduler;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.access.AccessDirectory;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.database.guild.GuildChannels;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.network.SnapshotDirectory;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.payment.Bookings;
import eu.nordtal.season.database.payment.PaymentGateway;
import eu.nordtal.season.database.payment.PaymentRequests;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.database.registration.Game;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.discordbot.access.SeasonStart;
import eu.nordtal.season.discordbot.access.discord.AccessRoles;
import eu.nordtal.season.discordbot.access.discord.LinkFlow;
import eu.nordtal.season.discordbot.access.discord.LockedRoles;
import eu.nordtal.season.discordbot.access.discord.ManagedMessages;
import eu.nordtal.season.discordbot.access.discord.PurchaseFlow;
import eu.nordtal.season.discordbot.access.discord.RedemptionLimit;
import eu.nordtal.season.discordbot.access.payment.BookingReaction;
import eu.nordtal.season.discordbot.access.payment.Purchases;
import eu.nordtal.season.discordbot.config.AccessSpec;
import eu.nordtal.season.discordbot.config.BotSettings;
import eu.nordtal.season.discordbot.config.BotSpec;
import eu.nordtal.season.discordbot.config.Configured;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordbot.config.OnboardingSpec;
import eu.nordtal.season.discordbot.discord.AdminRole;
import eu.nordtal.season.discordbot.discord.BotAccessEffects;
import eu.nordtal.season.discordbot.discord.BotInbox;
import eu.nordtal.season.discordbot.discord.ChannelList;
import eu.nordtal.season.discordbot.discord.GuildState;
import eu.nordtal.season.discordbot.discord.UpdateFeed;
import eu.nordtal.season.discordbot.onboarding.Onboarding;
import eu.nordtal.season.discordbot.onboarding.OnboardingFlow;
import eu.nordtal.season.discordbot.registration.RegisterFlow;
import eu.nordtal.season.discordbot.registration.RegisterMessages;
import eu.nordtal.season.discordbot.registration.Teams;
import eu.nordtal.season.discordbot.roles.GuildRoles;
import eu.nordtal.season.discordbot.status.StatusChannels;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.settings.Database;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.LanguageAndTimeSpec;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.settings.network.SeasonSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

@Slf4j
public class AccessBot implements AutoCloseable {

    private final Clock clock = NetworkTime.clock();

    private static final String SERVICE = "discord-bot";

    private final Database database;
    private final AccessDirectory access;
    private final JDA jda;

    /** {@code LISTEN} is session state, so the bot holds one connection for it. */
    private final SignalHub signals;

    /** Timeout of the liveness check and the reconnect. pgjdbc ignores it while waiting for notifications. */
    private static final int LISTENER_SOCKET_TIMEOUT_SECONDS = 30;

    /** Runs everything that blocks. A gateway thread must acknowledge an interaction within three seconds. */
    private final ProcessScheduler scheduler =
            new ProcessScheduler("access-bot", failure -> log.error("A task of the bot failed", failure));

    private final Executor statusLane = scheduler.serial();

    private record CoreServices(
            GuildLanguages languages,
            DiscordRenderer messages,
            StoredDocuments documents,
            Tiers tiers,
            PaymentRequests requests,
            Purchases purchases,
            Setting<OnboardingSpec> onboarding,
            String networkZone) {

        DocumentRenderer renderer() {
            return new DocumentRenderer(messages, documents);
        }
    }

    private record DiscordWiring(
            AdminLog admin,
            AccessRoles roles,
            BookingReaction bookings,
            PurchaseFlow purchaseFlow,
            AdminRole adminRole,
            GuildState guildState,
            Onboarding onboarding,
            BotAccessEffects inboxEffects,
            eu.nordtal.season.discordbot.announce.Announcements announcements,
            ChannelList channels) {}

    public AccessBot() throws InterruptedException, SettingsException {
        final DatabaseSpec databaseConfig = BotSettings.database().get();

        this.database = Database.open(databaseConfig, "access-bot");

        boolean started = false;
        try {
            SchemaCheck.validate(database.dataSource());
            final DatabaseSettings settings = BotSettings.stored(database.dataSource());
            final BotSpec botConfig = BotSettings.bot(settings).get();
            final LanguageAndTimeSpec languageAndTime =
                    settings.load(NetworkSettings.LANGUAGE_AND_TIME).get();
            final Languages network = NetworkSettings.languages(languageAndTime);
            final AccessSpec accessConfig =
                    BotSettings.access(settings, network).get();
            final SeasonSpec season = settings.load(NetworkSettings.SEASON).get();
            final Tiers tiers =
                    NetworkSettings.tiers(settings.load(NetworkSettings.PRICES).get());
            final Setting<OnboardingSpec> onboarding = BotSettings.onboarding(settings);

            this.access = AccessDirectory.using(database.dataSource(), clock);
            final PhaseDirectory phases = PhaseDirectory.using(database.dataSource(), clock);
            final UpdateDirectory updates = UpdateDirectory.using(database.dataSource());

            final CoreServices core =
                    loadCoreServices(accessConfig, network, season, languageAndTime, tiers, onboarding);
            this.jda = connectJda(botConfig);

            final DiscordWiring wiring = wireDiscord(jda, accessConfig, core, phases, botConfig.stewardUrl());
            publishAndReconcile(jda, core.languages(), core.tiers(), core.messages(), core.documents(), wiring);

            this.signals = finishStartup(
                    databaseConfig, settings, accessConfig, core, wiring, phases, updates, botConfig.stewardUrl());

            started = true;
            log.info("access-bot is up");
        } finally {
            if (!started) {
                database.close();
            }
        }
    }

    private SignalHub finishStartup(
            final DatabaseSpec databaseConfig,
            final DatabaseSettings settings,
            final AccessSpec accessConfig,
            final CoreServices core,
            final DiscordWiring wiring,
            final PhaseDirectory phases,
            final UpdateDirectory updates,
            final String stewardUrl) {
        // After the guild state reconcile, so the first tick renames against the reconciled state.
        final StatusChannels status = new StatusChannels(
                jda,
                core.languages(),
                core.renderer(),
                phases,
                SnapshotDirectory.using(database.dataSource()),
                clock,
                wiring.announcements());

        final UpdateFeed updateFeed =
                new UpdateFeed(updates, UpdateFeed.Board.of(wiring.admin()), core.renderer(), stewardUrl);
        updateFeed.start();

        schedule(accessConfig, wiring.roles(), wiring.onboarding());

        final Bookings booked = new Bookings(database.dataSource());
        // Last, because it refreshes on connect and uses JDA.
        final SignalHub hub = listen(
                databaseConfig,
                settings,
                wiring.onboarding(),
                updateFeed,
                status,
                wiring.purchaseFlow(),
                openInbox(new BotInbox(
                        wiring.inboxEffects(),
                        (announcement, line) -> wiring.announcements()
                                .post(
                                        line.language(),
                                        eu.nordtal.season.discordbot.announce.Announcements.text(
                                                core.renderer(), announcement, line)),
                        alert -> wiring.admin()
                                .postAlert(alert, wiring.adminRole().id(), updateFeed::failedCard),
                        payment -> wiring.bookings().tell(booked.told(payment)),
                        previewed -> wiring.roles().dmAndWait(previewed.person(), previewed(core, previewed)),
                        previewed -> wiring.roles().dmAndWait(previewed.person(), previewed(core, previewed)))),
                wiring.adminRole(),
                core.messages(),
                core.documents(),
                // An admin's save edits the managed messages at once.
                () -> scheduler.execute(
                        () -> publishManaged(jda, core.languages(), core.tiers(), core.messages(), core.documents())));

        // Last, so the marker file exists only after the constructor has finished.
        final var _ = Readiness.onDefaultPath(clock, log::warn).keepBeating(scheduler);

        return hub;
    }

    /** Draws an admin's text as members receive it, the values not given filled from the key's schema. */
    private static MessageCreateData previewed(final CoreServices core, final BotRequest.PreviewMessage previewed) {
        final MessagePreview preview = previewed.preview();
        final DiscordRenderer messages = core.messages();
        return MessageCreateData.fromContent(messages.format(
                Locales.parse(preview.language()),
                messages.raw().example(preview.key(), preview.given()),
                preview.text()));
    }

    /** Draws an admin's drafted document as members receive it. */
    private static MessageCreateData previewed(final CoreServices core, final BotRequest.PreviewDocument previewed) {
        final DocumentPreview preview = previewed.preview();
        return core.renderer()
                .message(
                        core.documents().drafted(preview),
                        List.of(core.languages().locales()),
                        Locales.parse(preview.language()));
    }

    private CoreServices loadCoreServices(
            final AccessSpec accessConfig,
            final Languages network,
            final SeasonSpec season,
            final LanguageAndTimeSpec languageAndTime,
            final Tiers tiers,
            final Setting<OnboardingSpec> onboarding) {
        final GuildLanguages languages = GuildLanguages.of(accessConfig, network);
        final DiscordRenderer messages = DiscordRenderer.of(
                Messages.load(AccessBot.class.getClassLoader(), DocumentRenderer.BUNDLES, languages.locales())
                        .within(NetworkSettings.environment(SERVICE, season, languageAndTime, Palette.DEFAULTS)));
        Configured.report(accessConfig, tiers, PaymentGateway.state(database.jdbi()));
        final PaymentRequests requests = new PaymentRequests(database.dataSource());
        final Purchases purchases = new Purchases(requests, tiers, accessConfig);

        return new CoreServices(
                languages,
                messages,
                new StoredDocuments(DocumentStore.using(database.dataSource())),
                tiers,
                requests,
                purchases,
                onboarding,
                languageAndTime.defaultTimeZone());
    }

    private JDA connectJda(final BotSpec botConfig) throws InterruptedException {
        final JDA connected = JDABuilder.createLight(botConfig.token())
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MODERATION)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .build()
                .awaitReady();
        connected
                .getPresence()
                .setPresence(
                        Activity.of(Activity.ActivityType.CUSTOM_STATUS, "It's that time of the year again..."), false);
        return connected;
    }

    private DiscordWiring wireDiscord(
            final JDA jda,
            final AccessSpec accessConfig,
            final CoreServices core,
            final PhaseDirectory phases,
            final String stewardUrl) {
        final AdminLog admin = new AdminLog(
                jda,
                accessConfig,
                AuditDirectory.using(database.dataSource()),
                AlertBook.using(database.dataSource()),
                core.renderer(),
                stewardUrl);
        // A period sold while season_phase.smp_start is NULL starts at the sale, not at the SMP opening.
        final SeasonStart seasonStart = new SeasonStart(phases, admin);
        final GuildRoles guildRoles = GuildRoles.stored(database.jdbi(), admin::alert);
        final Executor onboardingLane = scheduler.serial();
        final Onboarding onboarding = onboarding(jda, accessConfig, core, guildRoles, admin, onboardingLane);
        final AccessRoles roles = new AccessRoles(
                jda,
                accessConfig,
                core.languages(),
                guildRoles,
                onboarding,
                access,
                core.renderer(),
                admin,
                database.jdbi(),
                clock);
        final BookingReaction bookings =
                new BookingReaction(core.languages(), roles, admin, core.renderer(), jda, seasonStart);
        final AdminTree adminTree = AdminTree.using(database.dataSource());
        final GuildState guildState = new GuildState(jda, accessConfig, access, adminTree, database.jdbi());
        final AdminRole adminRole = new AdminRole(jda, accessConfig, guildRoles, adminTree, admin);
        final OnboardingFlow onboardingFlow = new OnboardingFlow(
                onboarding,
                core.languages(),
                guildRoles,
                core.renderer(),
                core.networkZone(),
                admin::alert,
                onboardingLane);

        final PurchaseFlow purchaseFlow = new PurchaseFlow(
                core.tiers(), core.purchases(), core.requests(), core.renderer(), roles, admin, scheduler, clock);

        return finishWiring(
                jda,
                accessConfig,
                core,
                admin,
                seasonStart,
                roles,
                bookings,
                guildState,
                adminRole,
                new Teams(database.jdbi(), Game.HUNGER_GAMES),
                purchaseFlow,
                onboarding,
                onboardingFlow,
                new LockedRoles(accessConfig.guildId(), guildRoles, roles, scheduler.serial()));
    }

    private Onboarding onboarding(
            final JDA jda,
            final AccessSpec accessConfig,
            final CoreServices core,
            final GuildRoles guildRoles,
            final AdminLog admin,
            final Executor lane) {
        return new Onboarding(
                jda,
                accessConfig.guildId(),
                core.languages(),
                fixedRoles(accessConfig),
                core.onboarding(),
                guildRoles,
                access,
                database.jdbi(),
                admin::alert,
                core.messages(),
                core.documents(),
                lane);
    }

    private static List<GuildRoles.Wanted> fixedRoles(final AccessSpec config) {
        return List.of(
                new GuildRoles.Wanted(
                        GuildRoles.ACCESS, config.roleNames().access().strip()),
                new GuildRoles.Wanted(
                        GuildRoles.DONOR, config.roleNames().donor().strip()),
                new GuildRoles.Wanted(
                        GuildRoles.ADMIN, config.roleNames().admin().strip()));
    }

    private DiscordWiring finishWiring(
            final JDA jda,
            final AccessSpec accessConfig,
            final CoreServices core,
            final AdminLog admin,
            final SeasonStart seasonStart,
            final AccessRoles roles,
            final BookingReaction bookings,
            final GuildState guildState,
            final AdminRole adminRole,
            final Teams teams,
            final PurchaseFlow purchaseFlow,
            final Onboarding onboarding,
            final OnboardingFlow onboardingFlow,
            final LockedRoles lockedRoles) {
        final ChannelList channels = new ChannelList(
                jda, accessConfig.guildId(), GuildChannels.using(database.dataSource()), scheduler.serial());
        jda.addEventListener(
                channels,
                guildState,
                onboarding,
                onboardingFlow,
                lockedRoles,
                purchaseFlow,
                new LinkFlow(
                        access,
                        roles,
                        core.renderer(),
                        admin,
                        new RedemptionLimit(accessConfig.linkCodeAttemptsPerHour(), clock),
                        scheduler),
                new RegisterFlow(jda, teams, access, core.renderer(), scheduler));

        final BotAccessEffects inboxEffects = new BotAccessEffects(access, roles, admin, seasonStart);
        final eu.nordtal.season.discordbot.announce.Announcements announcements =
                new eu.nordtal.season.discordbot.announce.Announcements(jda, core.languages(), log);

        final List<CommandData> commands = new ArrayList<>();
        commands.addAll(LinkFlow.commands());
        jda.updateCommands().addCommands(commands).queue();

        return new DiscordWiring(
                admin,
                roles,
                bookings,
                purchaseFlow,
                adminRole,
                guildState,
                onboarding,
                inboxEffects,
                announcements,
                channels);
    }

    private void publishAndReconcile(
            final JDA jda,
            final GuildLanguages languages,
            final Tiers tiers,
            final DiscordRenderer messages,
            final StoredDocuments documents,
            final DiscordWiring wiring) {
        // First, because every reconcile below reads a role that this call finds or creates.
        wiring.onboarding().resolveRoles();
        publishManaged(jda, languages, tiers, messages, documents);
        wiring.guildState().reconcile();
        wiring.channels().publish();
        wiring.roles().reconcile();
        wiring.adminRole().reconcile();
        wiring.onboarding().start();
    }

    private void schedule(final AccessSpec config, final AccessRoles roles, final Onboarding onboarding) {
        final Duration reconcile = Duration.ofMinutes(config.roleReconcileIntervalMinutes());
        final var _ = scheduler.every(reconcile, reconcile, roles::reconcile);
        final var _ = scheduler.every(reconcile, reconcile, onboarding::reconcile);

        final var _ = scheduler.every(Duration.ofHours(1), Duration.ofHours(1), () -> {
            roles.sweepExpiryNotices();
            roles.sweepLinkCodes();
        });
    }

    private Runnable openInbox(final BotInbox handler) {
        final Inbox<BotRequest> inbox = Inbox.takeOver(database.dataSource(), BotRequest.TABLE, "the bot");
        return () -> inbox.drain(handler);
    }

    /** Posts or edits every managed message, as the documents currently held draw it. */
    private void publishManaged(
            final JDA jda,
            final GuildLanguages languages,
            final Tiers tiers,
            final DiscordRenderer messages,
            final StoredDocuments documents) {
        new ManagedMessages(jda, languages, tiers, messages, documents, database.jdbi()).publishAll();
        new RegisterMessages(jda, languages, messages, documents, database.jdbi()).publishAll();
    }

    private SignalHub listen(
            final DatabaseSpec databaseConfig,
            final DatabaseSettings settings,
            final Onboarding onboarding,
            final UpdateFeed updateFeed,
            final StatusChannels status,
            final PurchaseFlow purchaseFlow,
            final Runnable drainInbox,
            final AdminRole adminRole,
            final DiscordRenderer messages,
            final StoredDocuments documents,
            final Runnable documentsChanged) {
        final SignalHub hub = SignalHub.open(
                databaseConfig.jdbcUrl(),
                databaseConfig.username(),
                databaseConfig.password(),
                LISTENER_SOCKET_TIMEOUT_SECONDS,
                "access-bot-signals",
                log);
        hub.on(Channel.PAYMENT, "waiting payment links", () -> scheduler.execute(purchaseFlow::fillIn));
        hub.on(Channel.BOT, "the bot inbox", () -> scheduler.execute(drainInbox));
        hub.on(Channel.ADMIN, "admin role", () -> scheduler.execute(adminRole::reconcile));
        hub.on(Channel.UPDATE, "the update feed", () -> updateFeed.submit(scheduler));
        if (status.configured()) {
            status.follow(hub, scheduler, statusLane);
        } else {
            log.info("No language has a status-channel; the sidebar status is off");
        }
        MessageOverrideStore.using(database.dataSource()).follow(messages.raw(), hub);
        documents.follow(hub, documentsChanged);
        settings.listen(hub, onboarding::settingsChanged);
        hub.start();
        return hub;
    }

    /** Leaves the readiness marker to go stale, which signals the shutdown. */
    @Override
    public void close() {
        log.info("Shutting down");
        signals.close();
        scheduler.close();
        try {
            jda.shutdown();
        } finally {
            database.close();
        }
    }

    private static final java.time.Duration FATAL_BACKOFF = java.time.Duration.ofSeconds(60);

    public static void main(final String[] args) throws InterruptedException {
        final AccessBot bot;
        try {
            bot = new AccessBot();
        } catch (final SettingsException e) {
            log.error("access-bot is not starting because its configuration could not be read.");
            log.error("{}", e.getMessage());
            System.exit(1);
            return;
        } catch (final net.dv8tion.jda.api.exceptions.InvalidTokenException badToken) {
            log.error("access-bot is not starting: Discord rejected the bot token.");
            log.error("Check NORDTAL_BOT_TOKEN in .env against the token in the Discord developer"
                    + " portal - a regenerated token invalidates the old one immediately.");
            backOffThenExit();
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close, "access-bot-shutdown"));
    }

    private static void backOffThenExit() {
        log.error(
                "Waiting {}s before exiting, so this container does not retry a login Discord has"
                        + " already refused every few seconds.",
                FATAL_BACKOFF.toSeconds());
        final var _ = Waiting.on(NetworkTime.clock()).sleep(FATAL_BACKOFF);
        System.exit(1);
    }
}
