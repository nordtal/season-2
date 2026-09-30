package eu.nordtal.s2.smp;

import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.language.Languages;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.access.AccessReader;
import eu.nordtal.s2.database.access.AdminOperators;
import eu.nordtal.s2.database.access.FullServerAdmission;
import eu.nordtal.s2.database.command.AllowlistDirectory;
import eu.nordtal.s2.database.command.CommandRequests;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.papercommon.command.CommandFilter;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.BukkitSmpEffects;
import eu.nordtal.s2.smp.config.DatabaseSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.db.SmpPool;
import eu.nordtal.s2.smp.duel.DuelListener;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SurfaceListener;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.grave.GraveListener;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.headstart.HeadStart;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.navigate.NavigateListener;
import eu.nordtal.s2.smp.npc.NpcListener;
import eu.nordtal.s2.smp.npc.NpcProtection;
import eu.nordtal.s2.smp.npc.SpawnNpc;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.JoinGate;
import eu.nordtal.s2.smp.player.PlayerComposition;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.progress.AdvancementListener;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;
import eu.nordtal.s2.smp.progress.StatisticPoller;
import eu.nordtal.s2.smp.protect.ProtectionListener;
import eu.nordtal.s2.smp.region.Boxes;
import eu.nordtal.s2.smp.region.ConfigBoxes;
import eu.nordtal.s2.smp.stage.BukkitCinematics;
import eu.nordtal.s2.smp.travel.BalloonDisplay;
import eu.nordtal.s2.smp.travel.BalloonListener;
import eu.nordtal.s2.smp.travel.PortalGate;
import eu.nordtal.s2.smp.welcome.SeasonWelcome;
import eu.nordtal.s2.smp.wheel.Wheel;
import eu.nordtal.s2.smp.wheel.WheelListener;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Bukkit;
import org.jdbi.v3.core.Jdbi;

/**
 * Everything {@link SmpPlugin#start()} wires up once its refusals have passed.
 *
 * It only returns values: NullAway's initializer check cannot follow a field assignment into another class.
 */
final class SmpStart {

    private SmpStart() {}

    record Database(
            HikariDataSource pool,
            Jdbi jdbi,
            SmpDao dao,
            Identities identities,
            Messages messages,
            PlayerLocales locales) {}

    static Database openDatabaseAndMessages(final SmpPlugin plugin, final DatabaseSpec database) {
        final HikariDataSource pool = SmpPool.open(database);
        final Jdbi jdbi = Jdbis.over(pool);
        final SmpDao dao = jdbi.onDemand(SmpDao.class);
        final Identities identities = new Identities(dao);

        // Later roots win, so this module's own keys override the shared ones.
        final Messages messages = Messages.load(
                        plugin.getClass().getClassLoader(),
                        java.util.List.of("messages/paper-common", "messages/commands", "messages/smp"),
                        plugin.getDataFolder().toPath().resolve("messages"),
                        Languages.NETWORK.locales())
                .within(MessageEnvironment.of(plugin.getName()));
        final PlayerLocales locales = new PlayerLocales(mcUuid -> dao.discordIdOf(mcUuid)
                .map(id -> Locales.parse(dao.localeOf(id).orElse(null)))
                .orElse(Locales.DEFAULT));
        return new Database(pool, jdbi, dao, identities, messages, locales);
    }

    record HudAndAnnouncer(SmpHud hud, CommandRequests requests, Announcer announcer) {}

    static HudAndAnnouncer startHudAndAnnouncer(final SmpPlugin plugin) {
        final SmpHud hud =
                new SmpHud(plugin, plugin.worlds, plugin.season, plugin.navigation, plugin.messages, plugin.locales);
        hud.start();

        // Discord announcements: one {@code command_request} row per language, fire and forget.
        final CommandRequests requests = CommandRequests.borrowing(plugin.pool);
        final Announcer announcer = new Announcer(
                requests,
                plugin.messages,
                BukkitSmpEffects.async(plugin),
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure),
                plugin.clock);
        return new HudAndAnnouncer(hud, requests, announcer);
    }

    record Surfaces(WorldEffects effects, PlayerComposition composition, PlayerSurfaces surfaces, Boards boards) {}

    static Surfaces wireEffectsAndSurfaces(final SmpPlugin plugin, final SmpSpec config) {
        // One instance: whichever object stamped a rocket must be the one {@code WorldEffects#onDamage} asks.
        final WorldEffects effects = new WorldEffects(plugin);
        plugin.getServer().getPluginManager().registerEvents(effects, plugin);

        final PlayerComposition composition =
                new PlayerComposition(() -> plugin.prestige, () -> plugin.prestigeColours);
        final PlayerSurfaces surfaces =
                new PlayerSurfaces(plugin, plugin.identities, composition, new MessageRenderer(plugin.messages));

        final Boards boards = new Boards(plugin, config, plugin.season, plugin.messages, plugin.locales);
        boards.start();
        return new Surfaces(effects, composition, surfaces, boards);
    }

    static AdminOperators startSurfaceRefreshAndOperatorSweep(final SmpPlugin plugin) {
        // Surfaces are drawn far more often than their data changes.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, plugin::refreshSurfaceData, 100L, 100L);

        // Sweeps before any join, since {@code ops.json} survives a crash.
        final AdminOperators operators = BukkitOps.create();
        operators.sweep();
        return operators;
    }

    record Presence(
            FullServerAdmission admission,
            SystemLines systemLines,
            BukkitCinematics cinematics,
            SeasonWelcome welcome) {}

    static Presence wirePresenceInputs(final SmpPlugin plugin, final SmpSpec config, final Surfaces surfaces) {
        // Shared with the watcher so the fullness check never reads a stale cache.
        final FullServerAdmission admission = new FullServerAdmission();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new JoinGate(plugin.identities, admission, plugin.messages, plugin.logger()), plugin);
        final SystemLines systemLines = new SystemLines(
                player ->
                        surfaces.composition().chatPrefix(player.getName(), plugin.identities.of(player.getUniqueId())),
                plugin.messages,
                plugin.locales);

        // Paper disables plugins before saving players, so a blindness would otherwise be saved too.
        final BukkitCinematics cinematics = new BukkitCinematics(plugin, plugin.sounds::play);
        plugin.getServer().getPluginManager().registerEvents(cinematics, plugin);
        final SeasonWelcome welcome =
                new SeasonWelcome(plugin, plugin.dao, plugin.identities, cinematics, config, plugin.worlds);
        return new Presence(admission, systemLines, cinematics, welcome);
    }

    static void registerPresenceListeners(
            final SmpPlugin plugin,
            final SmpSpec config,
            final Surfaces surfaces,
            final AdminOperators operators,
            final Presence presence) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new PresenceListener(
                                plugin,
                                plugin.identities,
                                surfaces.surfaces(),
                                plugin.locales,
                                operators,
                                presence.systemLines(),
                                presence.welcome()::onLanguageReady),
                        plugin);
        plugin.getServer().getPluginManager().registerEvents(presence.systemLines(), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new NavigateListener(plugin, plugin.dao, plugin.navigation, plugin.identities, plugin.sounds),
                        plugin);
        // The start event's winner is paid on their first join here, never by hunger-games.
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new HeadStart(
                                plugin,
                                plugin.dao,
                                plugin.identities,
                                surfaces.surfaces(),
                                config,
                                plugin.messages,
                                plugin.locales,
                                plugin.sounds),
                        plugin);
    }

    /** Keeps an admin an operator only for as long as the database still says so. */
    static AdminWatch buildAdminWatch(
            final SmpPlugin plugin,
            final AdminOperators operators,
            final FullServerAdmission admission,
            final PlayerSurfaces surfaces) {
        return new AdminWatch(
                plugin,
                AccessReader.using(plugin.pool, plugin.clock),
                operators,
                admission,
                admins -> {
                    if (plugin.identities.recordAdmins(admins)) {
                        surfaces.refreshAll();
                    }
                },
                plugin.logger());
    }

    record Progress(ObjectiveEngine engine, StatisticPoller poller) {}

    static Progress wireProgressEngine(final SmpPlugin plugin, final SmpSpec config, final WorldEffects effects) {
        final ObjectiveEngine engine = new ObjectiveEngine(
                plugin,
                plugin.dao,
                () -> plugin.track,
                plugin.season,
                plugin.worlds,
                plugin.identities,
                plugin.messages,
                plugin.locales,
                config,
                plugin.sounds,
                effects,
                plugin.announcer);
        final StatisticPoller poller = new StatisticPoller(plugin, () -> plugin.track, engine, plugin.identities);
        poller.start();
        return new Progress(engine, poller);
    }

    record Activities(DeathPenalty penalty, Wheel wheel, Graves graves, Duels duels) {}

    static Activities wireActivities(final SmpPlugin plugin, final SmpSpec config, final WorldEffects effects) {
        final Graves graves = new Graves(
                plugin,
                plugin.dao,
                plugin.identities,
                plugin.messages,
                plugin.locales,
                plugin.sounds,
                effects,
                config,
                plugin.clock);
        // Also immediately on start, so graves do not outlive their decay across downtime.
        Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, () -> graves.expire(config.graveMaxAgeHours()), 20L, 20L * 60L);
        // Every second on the main thread, but it writes only near expiry.
        Bukkit.getScheduler().runTaskTimer(plugin, graves::tickHolograms, 20L, 20L);
        final Duels duels = new Duels(
                plugin,
                plugin.dao,
                config,
                plugin.worlds,
                plugin.identities,
                plugin.messages,
                plugin.locales,
                plugin.sounds,
                effects,
                plugin.clock);

        final DeathPenalty penalty = new DeathPenalty(
                config.deathPenalty(), config.deathPenaltyListed(), java.util.Set.copyOf(config.deathCausesListed()));
        final Wheel wheel = new Wheel(
                plugin,
                plugin.dao,
                config,
                plugin.identities,
                plugin.messages,
                plugin.locales,
                plugin.sounds,
                plugin.clock);
        return new Activities(penalty, wheel, graves, duels);
    }

    static void registerActivityListeners(final SmpPlugin plugin, final SmpSpec config, final Activities activities) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new AdvancementListener(
                                plugin,
                                plugin.dao,
                                plugin.engine,
                                plugin.identities,
                                config,
                                plugin.messages,
                                plugin.locales,
                                plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new GraveListener(
                                plugin,
                                plugin.dao,
                                activities.graves(),
                                plugin.identities,
                                activities.penalty(),
                                activities.duels()::isInArena,
                                plugin.messages,
                                plugin.locales,
                                plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new DuelListener(plugin, config, activities.duels()), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new WheelListener(ConfigBoxes.wheelRegions(config), activities.wheel()), plugin);
    }

    static SpawnNpc wireNpc(final SmpPlugin plugin, final SmpSpec config) {
        // The only way a {@code HAND_IN} objective can be fulfilled.
        final SpawnNpc npc = new SpawnNpc(plugin, config);
        npc.spawn();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new NpcListener(
                                plugin,
                                plugin.dao,
                                npc,
                                () -> plugin.track,
                                plugin.engine,
                                plugin.identities,
                                config::wheelExtraSpinPercents,
                                plugin.messages,
                                plugin.locales,
                                plugin.sounds),
                        plugin);
        // Only keeps the figure standing against damage.
        plugin.getServer().getPluginManager().registerEvents(new NpcProtection(npc), plugin);
        return npc;
    }

    static BalloonDisplay restoreGravesAndRegisterWorld(
            final SmpPlugin plugin, final Boxes balloons, final Boxes regions, final WorldEffects effects) {
        // Graves outlive a restart, so they are read back once the world is up.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final var rows = plugin.dao.openGraves();
            Bukkit.getScheduler().runTask(plugin, () -> plugin.graves.restore(rows));
        });
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new ProtectionListener(
                                regions, plugin.identities, plugin.messages, plugin.locales, plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new BalloonListener(
                                balloons,
                                plugin.worlds,
                                plugin.season,
                                () -> plugin.track,
                                plugin.messages,
                                plugin.locales,
                                plugin.sounds,
                                effects),
                        plugin);
        // One item display per configured box.
        final BalloonDisplay balloonDisplay = new BalloonDisplay(plugin, balloons);
        balloonDisplay.spawn();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new PortalGate(
                                plugin, plugin.worlds, plugin.season, plugin.messages, plugin.locales, plugin.sounds),
                        plugin);

        // One listener for every menu; the grave inventory has a null holder, hence the predicate.
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new SurfaceListener(plugin.sounds, plugin.graves::isShowingGrave), plugin);
        return balloonDisplay;
    }

    /**
     * The command surface, built after the activities and started before the admin watch it shares a connection with.
     */
    record CommandLayer(
            BukkitSmpEffects chatEffects,
            ScheduledExecutorService commandWaiter,
            Outbox outbox,
            Messages sharedMessages,
            PaperCommandInbox inbox) {}

    static CommandLayer wireCommandLayer(final SmpPlugin plugin) {
        final AccessReader access = AccessReader.using(plugin.pool, plugin.clock);
        final BukkitSmpEffects chatEffects = new BukkitSmpEffects(
                plugin,
                BukkitSmpEffects.async(plugin),
                plugin.dao,
                plugin.engine,
                plugin.identities,
                access,
                plugin::reloadTrack);

        final ScheduledExecutorService commandWaiter =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                    final Thread thread = new Thread(task, plugin.getName() + "-command-waiter");
                    thread.setDaemon(true);
                    return thread;
                });
        final Outbox outbox = new Outbox(
                plugin.requests,
                commandWaiter,
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure),
                plugin.clock);

        // Built here so {@code /smp reload} can replace it.
        final Messages sharedMessages = PaperCommandInbox.sharedBundle(plugin);
        final PaperCommandInbox inbox =
                new PaperCommandInbox(plugin, Target.SMP, plugin.requests, access, sharedMessages);
        final SmpEffects inboxEffects = new BukkitSmpEffects(
                plugin, Runnable::run, plugin.dao, plugin.engine, plugin.identities, access, plugin::reloadTrack);
        SmpCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.start(plugin);
        return new CommandLayer(chatEffects, commandWaiter, outbox, sharedMessages, inbox);
    }

    static CommandFilter wireCommandFilterAndStartWatch(
            final SmpPlugin plugin, final SmpSpec config, final DatabaseSpec database, final PaperCommandInbox inbox) {
        // Fails open until an allowlist is published.
        final CommandFilter commandFilter = new CommandFilter(
                plugin,
                CommandFilter.Source.of(AllowlistDirectory.using(plugin.pool)),
                plugin.adminWatch::isAdmin,
                plugin.locales,
                plugin.messages,
                plugin.logger(),
                () -> plugin.colours,
                plugin.sounds::play);
        plugin.getServer().getPluginManager().registerEvents(commandFilter, plugin);
        commandFilter.start(java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()));

        plugin.adminWatch.start(
                java.time.Duration.ofSeconds(config.adminPollIntervalSeconds()),
                config.adminListenEnabled()
                        ? new AdminWatch.DatabaseConnection(
                                database.jdbcUrl(),
                                database.username(),
                                database.password(),
                                database.queryTimeoutSeconds())
                        : null,
                java.util.stream.Stream.concat(inbox.refreshes().stream(), commandFilter.refreshes().stream())
                        .toList(),
                java.util.stream.Stream.concat(inbox.channels().stream(), commandFilter.channels().stream())
                        .toList());
        return commandFilter;
    }
}
