package eu.nordtal.season.smp;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.announce.Announcer;
import eu.nordtal.season.smp.aura.DeathPenalty;
import eu.nordtal.season.smp.board.Boards;
import eu.nordtal.season.smp.config.DeathsSpec;
import eu.nordtal.season.smp.duel.DuelListener;
import eu.nordtal.season.smp.duel.Duels;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.grave.GraveDao;
import eu.nordtal.season.smp.grave.GraveListener;
import eu.nordtal.season.smp.grave.Graves;
import eu.nordtal.season.smp.hud.SmpHud;
import eu.nordtal.season.smp.navigate.NavigateListener;
import eu.nordtal.season.smp.navigate.PlaceDao;
import eu.nordtal.season.smp.npc.NpcListener;
import eu.nordtal.season.smp.npc.NpcProtection;
import eu.nordtal.season.smp.npc.SpawnNpc;
import eu.nordtal.season.smp.player.PlayerComposition;
import eu.nordtal.season.smp.player.PlayerSurfaces;
import eu.nordtal.season.smp.player.PlayerSurfacesListener;
import eu.nordtal.season.smp.progress.AdvancementListener;
import eu.nordtal.season.smp.progress.GateHolders;
import eu.nordtal.season.smp.progress.ObjectiveEngine;
import eu.nordtal.season.smp.progress.StatisticPoller;
import eu.nordtal.season.smp.protect.ProtectionListener;
import eu.nordtal.season.smp.stage.BukkitCinematics;
import eu.nordtal.season.smp.travel.PortalGate;
import eu.nordtal.season.smp.welcome.SeasonWelcome;
import eu.nordtal.season.smp.welcome.WelcomeDao;
import eu.nordtal.season.smp.wheel.ExtraSpins;
import eu.nordtal.season.smp.wheel.SpinDao;
import eu.nordtal.season.smp.wheel.Wheel;
import eu.nordtal.season.smp.wheel.WheelListener;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Creates the parts that {@link SmpPlugin#start()} wires after its refusals have passed.
 * The methods return their parts, because NullAway cannot follow a field assignment into another class.
 */
final class SmpStart {

    private SmpStart() {}

    static Announcer declareHudAndStartAnnouncer(final SmpPlugin plugin) {
        new SmpHud(plugin.worlds, plugin.season, plugin.navigation, plugin.renderer()).declareOn(plugin.hud());

        // Sends one request with every language to the bot's inbox and does not wait.
        final Announcer announcer = new Announcer(
                Inbox.over(plugin.pool(), BotRequest.TABLE),
                plugin.renderer().raw().locales(),
                PaperScheduler.of(plugin),
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure));
        return announcer;
    }

    record Surfaces(WorldEffects effects, PlayerComposition composition, PlayerSurfaces surfaces, Boards boards) {}

    static Surfaces wireEffectsAndSurfaces(final SmpPlugin plugin) {
        // All users share one instance. {@code WorldEffects#onDamage} must ask the object that marked the rocket.
        final WorldEffects effects = new WorldEffects(plugin);
        plugin.getServer().getPluginManager().registerEvents(effects, plugin);

        final PlayerComposition composition = new PlayerComposition(plugin::prestige, () -> plugin.prestigeColours);
        final PlayerSurfaces surfaces = new PlayerSurfaces(
                plugin,
                plugin.identities(),
                composition,
                plugin.renderer(),
                plugin.players(),
                plugin.nameTags.getNameTagManager());
        plugin.identities().whenChanged(surfaces::changed);

        final Boards boards =
                new Boards(plugin, plugin.boardSettings.get(), plugin.season, plugin.renderer(), plugin.identities());
        plugin.hud().every(Boards.REFRESH, boards::renderAll);
        return new Surfaces(effects, composition, surfaces, boards);
    }

    record Presence(SystemLines systemLines, BukkitCinematics cinematics, SeasonWelcome welcome) {}

    static Presence wirePresenceInputs(final SmpPlugin plugin, final Surfaces surfaces) {
        final SystemLines systemLines = plugin.systemLines();

        final BukkitCinematics cinematics = new BukkitCinematics(plugin, plugin.sounds::play);
        plugin.getServer().getPluginManager().registerEvents(cinematics, plugin);
        final SeasonWelcome welcome = new SeasonWelcome(
                plugin,
                plugin.jdbi().onDemand(WelcomeDao.class),
                plugin.identities(),
                cinematics,
                plugin.spawnSettings.get(),
                plugin.worlds);
        return new Presence(systemLines, cinematics, welcome);
    }

    static PlayerSurfacesListener registerSurfaceListener(
            final SmpPlugin plugin, final Surfaces surfaces, final Presence presence) {
        final PlayerSurfacesListener listener = new PlayerSurfacesListener(
                plugin, surfaces.surfaces(), presence.systemLines(), presence.welcome()::onLanguageReady);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new NavigateListener(
                                plugin,
                                plugin.jdbi().onDemand(PlaceDao.class),
                                plugin.navigation,
                                plugin.identities(),
                                plugin.renderer(),
                                plugin.sounds),
                        plugin);
        return listener;
    }

    record Progress(ObjectiveEngine engine, StatisticPoller poller, GateHolders gates) {}

    static Progress wireProgressEngine(final SmpPlugin plugin, final WorldEffects effects) {
        final ObjectiveEngine engine = new ObjectiveEngine(
                plugin,
                plugin.jdbi(),
                () -> plugin.track,
                plugin.season,
                plugin.worlds,
                plugin.identities(),
                plugin.renderer(),
                new ExtraSpins(plugin.jdbi().onDemand(SpinDao.class)),
                plugin.sounds,
                effects,
                plugin.announcer);
        final StatisticPoller poller = new StatisticPoller(plugin, () -> plugin.track, engine, plugin.identities());
        poller.start();
        final GateHolders gates = new GateHolders(
                () -> plugin.track,
                GateHolders.Server.running(),
                plugin.identities()::discordIdOf,
                PaperScheduler.of(plugin)::onMain,
                PaperScheduler.of(plugin),
                engine);
        plugin.getServer().getPluginManager().registerEvents(gates, plugin);
        return new Progress(engine, poller, gates);
    }

    record Activities(Supplier<DeathPenalty> penalty, Wheel wheel, Graves graves, Duels duels) {}

    static Activities wireActivities(final SmpPlugin plugin, final WorldEffects effects) {
        final DeathsSpec deaths = plugin.deathSettings.get();
        final Graves graves = new Graves(
                plugin,
                plugin.jdbi().onDemand(GraveDao.class),
                plugin.identities(),
                plugin.renderer(),
                plugin.documents(),
                plugin.sounds,
                effects,
                deaths,
                plugin.clock());
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        scheduler.every(Duration.ofSeconds(1), Duration.ofMinutes(1), () -> graves.expire(deaths.graveMaxAgeHours()));
        // Runs every second on the main thread. It writes only near expiry.
        scheduler.onMainEvery(Duration.ofSeconds(1), Duration.ofSeconds(1), graves::tickHolograms);
        final Duels duels = new Duels(
                plugin,
                plugin.aura,
                plugin.duelSettings.get(),
                plugin.borderSettings.get(),
                plugin.worlds,
                plugin.identities(),
                plugin.renderer(),
                plugin.sounds,
                effects,
                plugin.clock());

        // Built per death, so a saved penalty applies to the next one.
        final Supplier<DeathPenalty> penalty = () -> new DeathPenalty(
                deaths.deathPenalty(), deaths.deathPenaltyListed(), java.util.Set.copyOf(deaths.deathCausesListed()));
        final Wheel wheel = new Wheel(
                plugin,
                plugin.jdbi().onDemand(SpinDao.class),
                plugin.wheelSettings.get(),
                plugin.identities(),
                plugin.renderer(),
                plugin.documents(),
                plugin.sounds,
                plugin.clock());
        return new Activities(penalty, wheel, graves, duels);
    }

    static void registerActivityListeners(final SmpPlugin plugin, final Activities activities) {
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new AdvancementListener(
                                plugin,
                                plugin.jdbi(),
                                plugin.aura,
                                new ExtraSpins(plugin.jdbi().onDemand(SpinDao.class)),
                                plugin.engine,
                                plugin.identities(),
                                plugin.awardSettings.get(),
                                plugin.renderer(),
                                plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new GraveListener(
                                plugin,
                                plugin.aura,
                                activities.graves(),
                                plugin.identities(),
                                activities.penalty(),
                                activities.duels(),
                                plugin.renderer(),
                                plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new DuelListener(plugin, plugin.duelSettings.get(), activities.duels()), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new WheelListener(plugin.wheelSettings.get(), activities.wheel()), plugin);
    }

    static SpawnNpc wireNpc(final SmpPlugin plugin) {
        // A {@code HAND_IN} objective is fulfilled only through this NPC.
        final SpawnNpc npc = new SpawnNpc(
                plugin,
                plugin.npcSettings.get(),
                plugin.nameTags.getNameTagManager(),
                plugin.identities(),
                plugin.renderer());
        npc.spawn();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new NpcListener(
                                plugin,
                                npc,
                                plugin.season,
                                () -> plugin.track,
                                plugin.engine,
                                plugin.identities(),
                                plugin.renderer(),
                                plugin.documents(),
                                plugin.sounds),
                        plugin);
        plugin.getServer().getPluginManager().registerEvents(new NpcProtection(npc), plugin);
        return npc;
    }

    static void restoreGravesAndRegisterWorld(final SmpPlugin plugin) {
        plugin.graves.restore();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new ProtectionListener(
                                () -> plugin.spawnRegions, plugin.identities(), plugin.renderer(), plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new PortalGate(
                                plugin,
                                plugin.worlds,
                                plugin.season,
                                plugin.renderer(),
                                plugin.identities(),
                                plugin.sounds),
                        plugin);
    }
}
