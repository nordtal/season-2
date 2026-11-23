package eu.nordtal.season.smp;

import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.npc.NpcMenuStore;
import eu.nordtal.season.database.npc.NpcStore;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.packrendering.wheel.WheelTimetable;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.npc.DialogueTexts;
import eu.nordtal.season.papercommon.npc.Feel;
import eu.nordtal.season.papercommon.npc.NpcDialogues;
import eu.nordtal.season.papercommon.npc.NpcMenus;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.announce.Announcer;
import eu.nordtal.season.smp.aura.DeathPenalty;
import eu.nordtal.season.smp.board.Boards;
import eu.nordtal.season.smp.config.DeathsSpec;
import eu.nordtal.season.smp.duel.DuelListener;
import eu.nordtal.season.smp.duel.Duels;
import eu.nordtal.season.smp.eye.MapPortals;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.grave.GraveDao;
import eu.nordtal.season.smp.grave.GraveListener;
import eu.nordtal.season.smp.grave.Graves;
import eu.nordtal.season.smp.npc.NpcNamePlates;
import eu.nordtal.season.smp.npc.NpcTexts;
import eu.nordtal.season.smp.npc.ObjectivesMenu;
import eu.nordtal.season.smp.player.PlayerComposition;
import eu.nordtal.season.smp.player.PlayerSurfaces;
import eu.nordtal.season.smp.player.PlayerSurfacesListener;
import eu.nordtal.season.smp.progress.AdvancementListener;
import eu.nordtal.season.smp.progress.GateHolders;
import eu.nordtal.season.smp.progress.ObjectiveEngine;
import eu.nordtal.season.smp.progress.StatisticPoller;
import eu.nordtal.season.smp.protect.ProtectionListener;
import eu.nordtal.season.smp.rail.ChunkTickets;
import eu.nordtal.season.smp.rail.NetworkCarts;
import eu.nordtal.season.smp.rail.RailNetworks;
import eu.nordtal.season.smp.rail.RailStore;
import eu.nordtal.season.smp.rail.RideFeedback;
import eu.nordtal.season.smp.rail.RiderCartPackets;
import eu.nordtal.season.smp.rail.Rides;
import eu.nordtal.season.smp.rail.SpawnStation;
import eu.nordtal.season.smp.rail.StationMenus;
import eu.nordtal.season.smp.rail.StationRailListener;
import eu.nordtal.season.smp.stage.BukkitCinematics;
import eu.nordtal.season.smp.trader.PlantTrades;
import eu.nordtal.season.smp.travel.PortalGate;
import eu.nordtal.season.smp.wall.BorderWall;
import eu.nordtal.season.smp.welcome.SeasonWelcome;
import eu.nordtal.season.smp.welcome.WelcomeDao;
import eu.nordtal.season.smp.wheel.ExtraSpins;
import eu.nordtal.season.smp.wheel.ItemHandout;
import eu.nordtal.season.smp.wheel.PendingItems;
import eu.nordtal.season.smp.wheel.SpinDao;
import eu.nordtal.season.smp.wheel.Wheel;
import eu.nordtal.season.smp.wheel.WheelListener;
import eu.nordtal.season.smp.world.WorldRole;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Creates the parts that {@link SmpPlugin#start()} wires after its refusals have passed.
 * The methods return their parts, because NullAway cannot follow a field assignment into another class.
 */
final class SmpStart {

    private SmpStart() {}

    static Announcer startAnnouncer(final SmpPlugin plugin) {
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
        return listener;
    }

    record Progress(ObjectiveEngine engine, StatisticPoller poller, GateHolders gates) {}

    static Progress wireProgressEngine(final SmpPlugin plugin, final WorldEffects effects, final ItemHandout handout) {
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
                plugin.announcer,
                handout);
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

    record Activities(Supplier<DeathPenalty> penalty, ItemHandout handout, Wheel wheel, Graves graves, Duels duels) {}

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
        scheduler.onMainEvery(
                Duration.ofSeconds(1), Duration.ofMinutes(1), () -> graves.expire(deaths.graveMaxAgeHours()));
        // Runs every second on the main thread. It writes only near expiry.
        scheduler.onMainEvery(Duration.ofSeconds(1), Duration.ofSeconds(1), graves::tickHolograms);
        final Duels duels = new Duels(
                plugin,
                plugin.aura,
                plugin.duelSettings.get(),
                plugin.identities(),
                plugin.renderer(),
                plugin.documents(),
                plugin.dialogs(),
                plugin.sounds,
                effects,
                plugin.clock());

        // Built per death, so a saved penalty applies to the next one.
        final Supplier<DeathPenalty> penalty = () -> new DeathPenalty(
                deaths.deathPenalty(), deaths.deathPenaltyListed(), java.util.Set.copyOf(deaths.deathCausesListed()));
        final ItemHandout handout = new ItemHandout(
                plugin,
                plugin.jdbi().onDemand(PendingItems.class),
                plugin.identities(),
                plugin.renderer(),
                plugin.itemWork,
                () -> plugin.worlds.world(WorldRole.NORDTAL).orElseThrow());
        plugin.getServer().getPluginManager().registerEvents(handout, plugin);
        final Wheel wheel = new Wheel(
                plugin,
                plugin.jdbi().onDemand(SpinDao.class),
                handout,
                plugin.itemWork,
                plugin.wheelSettings.get(),
                plugin.identities(),
                plugin.renderer(),
                plugin.documents(),
                plugin.sounds,
                plugin.dialogs(),
                WheelTimetable.load(),
                plugin.clock());
        return new Activities(penalty, handout, wheel, graves, duels);
    }

    static void registerActivityListeners(final SmpPlugin plugin, final Activities activities) {
        plugin.getServer().getPluginManager().registerEvents(new PlantTrades(), plugin);
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
        plugin.getServer().getPluginManager().registerEvents(new DuelListener(plugin, activities.duels()), plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new WheelListener(plugin.wheelSettings.get(), activities.wheel()), plugin);
    }

    static NpcDialogues wireNpc(final SmpPlugin plugin) {
        // A {@code HAND_IN} objective is fulfilled only through the menu an NPC's dialogue hands off to.
        final ObjectivesMenu objectives = new ObjectivesMenu(
                plugin,
                plugin.season,
                () -> plugin.track,
                plugin.engine,
                plugin.identities(),
                plugin.renderer(),
                plugin.documents(),
                plugin.dialogs(),
                plugin.sounds);
        final Wheel wheel = Objects.requireNonNull(plugin.wheel, "the wheel starts before the NPCs");
        final NpcMenus menus = new NpcMenus()
                .register(
                        ObjectivesMenu.MENU,
                        locale -> NpcTexts.objectivesHint(plugin.renderer(), locale),
                        objectives::openObjectives)
                .register("wheel", locale -> NpcTexts.wheelHint(plugin.renderer(), locale), wheel::open);
        final NpcStore store = NpcStore.using(plugin.pool());
        PaperScheduler.of(plugin)
                .execute(() -> NpcMenuStore.using(plugin.pool()).publish("smp", menus.describe()));
        final NpcDialogues dialogues = new NpcDialogues(
                plugin,
                store,
                new NpcDialogues.Parts(
                        plugin.hud(),
                        plugin.identities()::languageOf,
                        DialogueTexts.labels(plugin.renderer(), menus),
                        menus,
                        new NpcNamePlates(plugin.nameTags.getNameTagManager(), plugin.identities()::languageOf),
                        () -> Feel.of(plugin.npcDialogueSettings.get())));
        dialogues.start();
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        store.follow(plugin.hub(), npcs -> scheduler.onMain(() -> dialogues.apply(npcs)));
        return dialogues;
    }

    static void restoreGravesAndRegisterWorld(final SmpPlugin plugin) {
        plugin.graves.restore();
        final Duels duels = Objects.requireNonNull(plugin.duels, "the duels start before the world");
        duels.restoreLeftover();
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new ProtectionListener(
                                () -> plugin.protectedAreas,
                                duels,
                                plugin.identities(),
                                plugin.renderer(),
                                plugin.sounds),
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

    /** Scans the worlds for the map's End portals, to which every thrown eye of ender flies. */
    static MapPortals wireMapPortals(final SmpPlugin plugin) {
        final MapPortals portals = new MapPortals(plugin.getDataPath(), plugin.getLogger(), PaperScheduler.of(plugin));
        plugin.getServer().getPluginManager().registerEvents(portals, plugin);
        portals.start(plugin.getServer().getWorlds());
        return portals;
    }

    /** Starts the custom border's wall with the settings it has. */
    static BorderWall wireBorderWall(final SmpPlugin plugin) {
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        final BorderWall wall = new BorderWall(
                plugin.clock(),
                task -> scheduler.onMainAfter(PaperScheduler.TICK, task),
                player -> {
                    // Read on each push: the sounds are set up after the wall and reloaded in place.
                    final SmpSounds sounds = plugin.sounds;
                    if (sounds != null) {
                        sounds.play(player, Feedback.REFUSED);
                    }
                    player.sendActionBar(Lines.joined(plugin.renderer()
                            .format(plugin.identities().languageOf(player.getUniqueId()), Sent.of(BorderWall.LIMIT))));
                },
                player -> plugin.identities().of(player.getUniqueId()).admin());
        scheduler.onMainEvery(
                PaperScheduler.TICK,
                PaperScheduler.TICK,
                () -> wall.tick(plugin.getServer().getOnlinePlayers()));
        plugin.getServer().getPluginManager().registerEvents(wall, plugin);
        wall.apply(plugin.customBorderSettings.get(), plugin.getServer().getOnlinePlayers());
        return wall;
    }

    /** The rail networks and the rides on them, which stop together. */
    record Rails(RailNetworks networks, Rides rides, RiderCartPackets packets) {}

    /**
     * Starts the rail networks and the rides that drive minecarts along them.
     * The networks bring their recipe, their stored state, their walk every tick, their listener and menus, and the
     * spawn station that the settings place.
     */
    static Rails wireRails(final SmpPlugin plugin, final WorldEffects effects) {
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        final ChunkTickets tickets = new ChunkTickets(plugin);
        final RailNetworks rails = new RailNetworks(
                plugin, tickets, new RailStore(plugin.jdbi(), scheduler, plugin.getLogger()), plugin.clock());
        rails.start(scheduler::onMain);
        scheduler.onMainEvery(PaperScheduler.TICK, PaperScheduler.TICK, rails::tick);
        final SpawnStation spawn = new SpawnStation(
                rails,
                tickets,
                () -> SpawnStation.target(plugin.railSettings.get().spawnStation()),
                plugin.getLogger()::warning);
        scheduler.onMainEvery(Duration.ofSeconds(1), Duration.ofSeconds(1), spawn::tick);
        final Rides rides = new Rides(scheduler, rails, tickets, plugin.railSettings::get, plugin.sounds);
        rides.whenSwitched(effects::junctionSwitched);
        final RiderCartPackets packets = RiderCartPackets.register();
        rides.tell(packets);
        scheduler.onMainEvery(PaperScheduler.TICK, PaperScheduler.TICK, rides::tick);
        plugin.getServer().getPluginManager().registerEvents(rides, plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new StationRailListener(rails, plugin.identities(), plugin.sounds, plugin.renderer()), plugin);
        final StationMenus menus = new StationMenus(
                rails,
                rides,
                plugin.renderer(),
                plugin.documents(),
                plugin.identities(),
                plugin.sounds,
                scheduler,
                plugin.dialogs(),
                () -> plugin.railSettings.get().stationWidth());
        plugin.getServer().getPluginManager().registerEvents(menus, plugin);
        final RideFeedback feedback = new RideFeedback(
                plugin.renderer(),
                plugin.identities(),
                plugin.sounds,
                plugin.clock(),
                scheduler.mainThread(),
                menus::waiting);
        rides.watch(feedback);
        plugin.hud().declare(feedback);
        plugin.hud().declare(feedback::below);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        NetworkCarts.of(
                                rails, rides, menus, plugin.renderer(), plugin.identities(), plugin.sounds, scheduler),
                        plugin);
        return new Rails(rails, rides, packets);
    }
}
