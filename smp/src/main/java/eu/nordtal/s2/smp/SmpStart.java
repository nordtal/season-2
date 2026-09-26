package eu.nordtal.s2.smp;

import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.remote.Outbox;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.smp.SmpEffects;
import eu.nordtal.s2.common.access.AdminOperators;
import eu.nordtal.s2.common.access.FullServerAdmission;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.papercommon.access.AdminWatch;
import eu.nordtal.s2.papercommon.access.BukkitOps;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.papercommon.command.PaperCommandInbox;
import eu.nordtal.s2.papercommon.stage.BukkitCinematics;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.command.BukkitSmpEffects;
import eu.nordtal.s2.smp.config.DatabaseSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.JoinGate;
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
import eu.nordtal.s2.smp.player.PlayerComposition;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.progress.AdvancementListener;
import eu.nordtal.s2.smp.progress.ObjectiveEngine;
import eu.nordtal.s2.smp.progress.StatisticPoller;
import eu.nordtal.s2.smp.protect.ProtectionListener;
import eu.nordtal.s2.smp.region.Boxes;
import eu.nordtal.s2.smp.region.ConfigBoxes;
import eu.nordtal.s2.smp.travel.BalloonDisplay;
import eu.nordtal.s2.smp.travel.BalloonListener;
import eu.nordtal.s2.smp.travel.PortalGate;
import eu.nordtal.s2.smp.welcome.SeasonWelcome;
import eu.nordtal.s2.smp.wheel.Wheel;
import eu.nordtal.s2.smp.wheel.WheelListener;
import java.util.concurrent.ScheduledExecutorService;
import org.bukkit.Bukkit;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

/**
 * Everything {@link SmpPlugin#start()} wires up once its refusals have already passed.
 *
 * A separate file rather than a longer {@code start()}, so that method stays the thin sequence of steps it reads as.
 * <b>This class never assigns a field of {@link SmpPlugin} directly</b> - it only builds and returns values; every
 * assignment happens in {@code SmpPlugin} itself, in the same statement NullAway's {@code KnownInitializers} check
 * can see, because that check cannot follow a call into a different class.
 */
final class SmpStart {

    private SmpStart() {}

    /** The pool, JDBI handle, DAO, identities cache and messaging built for the plugin. */
    record Database(
            com.zaxxer.hikari.HikariDataSource pool,
            Jdbi jdbi,
            SmpDao dao,
            Identities identities,
            Messages messages,
            PlayerLocales locales) {}

    static Database openDatabaseAndMessages(final SmpPlugin plugin, final DatabaseSpec database) {
        final com.zaxxer.hikari.HikariDataSource pool = SmpPool.open(database);
        final Jdbi jdbi = Jdbi.create(pool).installPlugin(new SqlObjectPlugin()).installPlugin(new PostgresPlugin());
        final SmpDao dao = jdbi.onDemand(SmpDao.class);
        final Identities identities = new Identities(dao);

        // Later message roots win, so this module's own keys override the shared ones.
        final Messages messages = Messages.load(
                plugin.getClass().getClassLoader(),
                java.util.List.of("messages/paper-common", "messages/commands", "messages/smp"),
                plugin.getDataFolder().toPath().resolve("messages"),
                java.util.Locale.ENGLISH,
                java.util.Locale.GERMAN);
        final PlayerLocales locales = new PlayerLocales(mcUuid -> dao.discordIdOf(mcUuid)
                .map(id -> eu.nordtal.s2.common.message.Locales.parse(
                        dao.localeOf(id).orElse(null)))
                .orElse(eu.nordtal.s2.common.message.Locales.DEFAULT));
        return new Database(pool, jdbi, dao, identities, messages, locales);
    }

    record HudAndAnnouncer(
            SmpHud hud,
            eu.nordtal.s2.common.command.CommandRequests requests,
            eu.nordtal.s2.smp.announce.Announcer announcer) {}

    static HudAndAnnouncer startHudAndAnnouncer(final SmpPlugin plugin) {
        final SmpHud hud =
                new SmpHud(plugin, plugin.worlds, plugin.season, plugin.navigation, plugin.messages, plugin.locales);
        hud.start();

        // Discord announcements ride on this: one command_request row per language, fire and forget.
        final eu.nordtal.s2.common.command.CommandRequests requests =
                eu.nordtal.s2.common.command.CommandRequests.borrowing(plugin.pool);
        final eu.nordtal.s2.smp.announce.Announcer announcer = new eu.nordtal.s2.smp.announce.Announcer(
                requests,
                plugin.messages,
                BukkitSmpEffects.async(plugin),
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure));
        return new HudAndAnnouncer(hud, requests, announcer);
    }

    /** {@code effects}, the two objects that read the player composition, and the started boards. */
    record Surfaces(WorldEffects effects, PlayerComposition composition, PlayerSurfaces surfaces, Boards boards) {}

    static Surfaces wireEffectsAndSurfaces(final SmpPlugin plugin, final SmpSpec config) {
        // One instance: whichever object stamped a rocket must be the one WorldEffects#onDamage asks.
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
        // One async sweep for everything a surface reads from the database, drawn far more often than it changes.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, plugin::refreshSurfaceData, 100L, 100L);
        // The one main-thread read of the player collection, for /smp status - see the field.
        Bukkit.getScheduler()
                .runTaskTimer(
                        plugin, () -> plugin.online = Bukkit.getOnlinePlayers().size(), 20L, 20L);

        // The sweep runs before any join can be handled, since ops.json is persistent across a crash.
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
        // One instance shared with the watcher below, so the fullness check never reads a stale cache.
        final FullServerAdmission admission = new FullServerAdmission();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new JoinGate(plugin.identities, admission, plugin.messages, plugin.logger()), plugin);
        // The composition is this server's half of the shared lines; the rest is :paper-common's, on every backend.
        final SystemLines systemLines = new SystemLines(
                player ->
                        surfaces.composition().chatPrefix(player.getName(), plugin.identities.of(player.getUniqueId())),
                plugin.messages,
                plugin.locales);

        // Stopped at disable because Paper disables plugins before saving players, or a blindness gets saved too.
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
                                presence.welcome()),
                        plugin);
        plugin.getServer().getPluginManager().registerEvents(presence.systemLines(), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new NavigateListener(plugin, plugin.dao, plugin.navigation, plugin.identities, plugin.sounds),
                        plugin);
        // The start event's winner is paid on their first join here, never by hunger-games; see HeadStart for why.
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

    /**
     * Keeps an admin an operator only for as long as the database still says so.
     *
     * The admin flag also feeds {@link Identities}, so a nametag's admin tag is redrawn only when the roster
     * actually changed.
     */
    static AdminWatch buildAdminWatch(
            final SmpPlugin plugin,
            final AdminOperators operators,
            final FullServerAdmission admission,
            final PlayerSurfaces surfaces) {
        return new AdminWatch(
                plugin,
                eu.nordtal.s2.common.access.AccessDirectory.using(plugin.pool),
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
                plugin, plugin.dao, plugin.identities, plugin.messages, plugin.locales, plugin.sounds, effects, config);
        // Grave decay runs once a minute and immediately on start, so graves are not left standing after downtime.
        Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, () -> graves.expire(config.graveMaxAgeHours()), 20L, 20L * 60L);
        // Ticks once a second on the main thread since TextDisplay#text is a packet, but writes only near expiry.
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
                effects);

        final DeathPenalty penalty = new DeathPenalty(
                config.deathPenalty(), config.deathPenaltyListed(), java.util.Set.copyOf(config.deathCausesListed()));
        final Wheel wheel = new Wheel(
                plugin, plugin.dao, config, plugin.identities, plugin.messages, plugin.locales, plugin.sounds);
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
        // The figure in the tavern, and the only way a HAND_IN objective can be fulfilled.
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
        // Separate from NpcListener, which is what the figure is for; this only keeps it standing against damage.
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
        // The balloon a player sees, as opposed to the box they step into: one item display per configured box.
        final BalloonDisplay balloonDisplay = new BalloonDisplay(plugin, balloons);
        balloonDisplay.spawn();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new PortalGate(
                                plugin, plugin.worlds, plugin.season, plugin.messages, plugin.locales, plugin.sounds),
                        plugin);

        // One listener for every menu's SURFACE_OPEN/CLOSE; the grave inventory has a null holder, hence the predicate.
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new SurfaceListener(plugin.sounds, plugin.graves::isShowingGrave), plugin);
        return balloonDisplay;
    }

    /**
     * The command surface: built after the activities, started before the admin watch.
     *
     * Half of what /smp does goes through the objective engine, and the inbox rides on the admin watch's LISTEN
     * connection, which carries both nordtal_admin and nordtal_command.
     */
    record CommandLayer(
            BukkitSmpEffects chatEffects,
            ScheduledExecutorService commandWaiter,
            Outbox outbox,
            Messages sharedMessages,
            PaperCommandInbox inbox) {}

    static CommandLayer wireCommandLayer(final SmpPlugin plugin) {
        final eu.nordtal.s2.common.access.AccessDirectory access =
                eu.nordtal.s2.common.access.AccessDirectory.using(plugin.pool);
        final BukkitSmpEffects chatEffects = new BukkitSmpEffects(
                plugin,
                BukkitSmpEffects.async(plugin),
                plugin.jdbi,
                plugin.dao,
                plugin.engine,
                plugin.identities,
                access,
                plugin::reloadTrack,
                plugin::status);

        final ScheduledExecutorService commandWaiter =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
                    final Thread thread = new Thread(task, plugin.getName() + "-command-waiter");
                    thread.setDaemon(true);
                    return thread;
                });
        final Outbox outbox = new Outbox(
                plugin.requests,
                commandWaiter,
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure));

        // Built here rather than in the inbox so /smp reload can replace it, instead of answering with stale wording.
        final Messages sharedMessages = PaperCommandInbox.sharedBundle(plugin);
        final PaperCommandInbox inbox =
                new PaperCommandInbox(plugin, Target.SMP, plugin.requests, access, sharedMessages);
        // Inline, on purpose - see BukkitSmpEffects' own field comment on SmpPlugin.
        final SmpEffects inboxEffects = new BukkitSmpEffects(
                plugin,
                Runnable::run,
                plugin.jdbi,
                plugin.dao,
                plugin.engine,
                plugin.identities,
                access,
                plugin::reloadTrack,
                plugin::status);
        SmpCommands.all().forEach(command -> inbox.register(command, inboxEffects));
        inbox.start(plugin);
        return new CommandLayer(chatEffects, commandWaiter, outbox, sharedMessages, inbox);
    }

    static eu.nordtal.s2.papercommon.command.CommandFilter wireCommandFilterAndStartWatch(
            final SmpPlugin plugin, final SmpSpec config, final DatabaseSpec database, final PaperCommandInbox inbox) {
        // The command allowlist: what this server tells a client exists at all; fails open until one is published.
        final eu.nordtal.s2.papercommon.command.CommandFilter commandFilter =
                new eu.nordtal.s2.papercommon.command.CommandFilter(
                        plugin,
                        eu.nordtal.s2.papercommon.command.CommandFilter.Source.of(
                                eu.nordtal.s2.common.command.AllowlistDirectory.using(plugin.pool)),
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
