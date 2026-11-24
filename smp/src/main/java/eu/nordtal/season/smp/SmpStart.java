package eu.nordtal.season.smp;

import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.npc.NpcMenuStore;
import eu.nordtal.season.database.npc.NpcStore;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.packrendering.wheel.WheelTimetable;
import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.papercommon.npc.DialogueTexts;
import eu.nordtal.season.papercommon.npc.Feel;
import eu.nordtal.season.papercommon.npc.NpcDialogues;
import eu.nordtal.season.papercommon.npc.NpcMenus;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.area.AreaEditor;
import eu.nordtal.season.smp.aura.DeathPenalty;
import eu.nordtal.season.smp.board.Boards;
import eu.nordtal.season.smp.capture.CaptureFeedback;
import eu.nordtal.season.smp.capture.CaptureSessions;
import eu.nordtal.season.smp.config.DeathsSpec;
import eu.nordtal.season.smp.config.WheelBlockSpec;
import eu.nordtal.season.smp.duel.DuelListener;
import eu.nordtal.season.smp.duel.DuelStakes;
import eu.nordtal.season.smp.duel.Duels;
import eu.nordtal.season.smp.duel.Escrow;
import eu.nordtal.season.smp.eye.MapPortals;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.grave.GraveDao;
import eu.nordtal.season.smp.grave.GraveListener;
import eu.nordtal.season.smp.grave.Graves;
import eu.nordtal.season.smp.item.CustomItems;
import eu.nordtal.season.smp.item.ItemsMenu;
import eu.nordtal.season.smp.npc.NpcNamePlates;
import eu.nordtal.season.smp.npc.NpcTexts;
import eu.nordtal.season.smp.npc.ObjectivesMenu;
import eu.nordtal.season.smp.pack.PackSwap;
import eu.nordtal.season.smp.pending.PendingItems;
import eu.nordtal.season.smp.player.PlayerComposition;
import eu.nordtal.season.smp.player.PlayerSurfaces;
import eu.nordtal.season.smp.player.PlayerSurfacesListener;
import eu.nordtal.season.smp.progress.AdvancementListener;
import eu.nordtal.season.smp.progress.GateHolders;
import eu.nordtal.season.smp.progress.ObjectiveEngine;
import eu.nordtal.season.smp.progress.StatisticPoller;
import eu.nordtal.season.smp.protect.ProtectionListener;
import eu.nordtal.season.smp.protect.SpawnExceptionCapture;
import eu.nordtal.season.smp.protect.SpawnExceptions;
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
import eu.nordtal.season.smp.takein.ItemTakeIn;
import eu.nordtal.season.smp.takein.PositionTake;
import eu.nordtal.season.smp.trader.PlantTrades;
import eu.nordtal.season.smp.travel.PortalGate;
import eu.nordtal.season.smp.tunnel.BlockPos;
import eu.nordtal.season.smp.tunnel.TunnelMiner;
import eu.nordtal.season.smp.tunnel.TunnelMinerItems;
import eu.nordtal.season.smp.tunnel.TunnelProtections;
import eu.nordtal.season.smp.wall.BorderWall;
import eu.nordtal.season.smp.welcome.SeasonWelcome;
import eu.nordtal.season.smp.welcome.WelcomeDao;
import eu.nordtal.season.smp.wheel.ExtraSpins;
import eu.nordtal.season.smp.wheel.ItemHandout;
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
                regions -> {
                    final java.util.Set<String> current = plugin.season.borderRegions();
                    final java.util.List<String> stillOpen =
                            regions.stream().filter(current::contains).toList();
                    if (!stillOpen.isEmpty()) {
                        final BorderWall wall = Objects.requireNonNull(plugin.borderWall);
                        wall.open(stillOpen, plugin.getServer().getOnlinePlayers());
                        plugin.applyCustomLimit();
                    }
                },
                plugin.identities(),
                plugin.renderer(),
                new ExtraSpins(plugin.jdbi().onDemand(SpinDao.class)),
                plugin.sounds,
                effects,
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
        final ItemHandout handout = new ItemHandout(
                plugin,
                plugin.jdbi().onDemand(PendingItems.class),
                plugin.identities(),
                plugin.renderer(),
                plugin.itemWork,
                () -> plugin.worlds.world(WorldRole.NORDTAL).orElseThrow(),
                player -> {
                    final Duels running = plugin.duels;
                    return running != null && running.holdsInventory(player);
                });
        plugin.getServer().getPluginManager().registerEvents(handout, plugin);
        final Duels duels = wireDuels(plugin, effects, handout, scheduler);

        // Built per death, so a saved penalty applies to the next one.
        final Supplier<DeathPenalty> penalty = () -> new DeathPenalty(
                deaths.deathPenalty(), deaths.deathPenaltyListed(), java.util.Set.copyOf(deaths.deathCausesListed()));
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

    /** Builds the duels with the escrow that holds their stakes, and refunds what a stop or crash left held. */
    private static Duels wireDuels(
            final SmpPlugin plugin,
            final WorldEffects effects,
            final ItemHandout handout,
            final PaperScheduler scheduler) {
        final Escrow escrow =
                new Escrow(plugin.jdbi().onDemand(DuelStakes.class), handout, plugin.itemWork, plugin.getLogger());
        escrow.refundLeftover();
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
                plugin.clock(),
                escrow);
        scheduler.onMainEvery(
                PaperScheduler.TICK,
                PaperScheduler.TICK,
                () -> duels.tick(plugin.getServer().getOnlinePlayers()));
        duels.examples(plugin::example);
        return duels;
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
        final ItemHandout handout = Objects.requireNonNull(plugin.handout, "the handout starts before the NPCs");
        final ObjectivesMenu objectives = new ObjectivesMenu(
                plugin,
                plugin.season,
                () -> plugin.track,
                plugin.engine,
                plugin.identities(),
                plugin.renderer(),
                plugin.documents(),
                plugin.dialogs(),
                plugin.sounds,
                handout::store);
        objectives.examples(plugin::example);
        final Wheel wheel = Objects.requireNonNull(plugin.wheel, "the wheel starts before the NPCs");
        final NpcMenus menus = new NpcMenus()
                .register(
                        ObjectivesMenu.MENU,
                        locale -> NpcTexts.objectivesHint(plugin.renderer(), locale),
                        (player, onClose) -> objectives.openObjectives(player, onClose))
                .register(
                        "wheel",
                        locale -> NpcTexts.wheelHint(plugin.renderer(), locale),
                        (player, onClose) -> wheel.open(player, onClose));
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
                        DialogueTexts.skipNotice(plugin.renderer()),
                        menus,
                        new NpcNamePlates(plugin.nameTags.getNameTagManager(), plugin.identities()::languageOf),
                        () -> Feel.of(plugin.npcDialogueSettings.get())));
        dialogues.start();
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        store.follow(plugin.hub(), npcs -> scheduler.onMain(() -> dialogues.apply(npcs)));
        return dialogues;
    }

    /** Wires spawn protection, rails, portals, border wall, capture modes and the SMP inbox. */
    static void wireCapturesAndProtect(final SmpPlugin plugin, final Surfaces wired) {
        final CaptureSessions captureSessions = new CaptureSessions();
        final CaptureFeedback captureFeedback =
                new CaptureFeedback(plugin.identities(), plugin.renderer(), plugin.sounds);
        final SpawnExceptions exceptions = wireSpawnExceptions(plugin);
        plugin.spawnExceptions = exceptions;
        restoreGravesAndRegisterWorld(plugin, exceptions);
        plugin.rails = wireRails(plugin, wired.effects());
        plugin.portals = wireMapPortals(plugin);
        plugin.borderWall = wireBorderWall(plugin);
        plugin.customItems = wireCustomItems(plugin);
        plugin.tunnelMiner = wireTunnelMiner(plugin);
        final AreaEditor editor =
                wireAreaEditor(plugin, wired.effects(), plugin.worldsSpec().worldNordtal(), captureSessions);
        plugin.areaEditor = editor;
        final SpawnExceptionCapture exceptionCapture =
                wireSpawnExceptionCapture(plugin, captureSessions, captureFeedback);
        plugin.spawnExceptionCapture = exceptionCapture;
        final ItemTakeIn takeIn = new ItemTakeIn(
                plugin, plugin.pool(), plugin.renderer(), plugin.documents(), plugin.identities(), captureSessions);
        takeIn.watch(plugin.hub());
        ItemTakeIn.examples(plugin::example);
        final PackSwap packSwap = new PackSwap(plugin, plugin.pool());
        plugin.getServer().getPluginManager().registerEvents(packSwap, plugin);
        plugin.answerRequests(
                takeIn,
                new PositionTake(plugin, plugin.pool(), captureSessions, captureFeedback),
                packSwap,
                editor,
                exceptionCapture);
    }

    /** Starts spawn door and trapdoor exceptions, their display markers and their auto-reset tick. */
    static SpawnExceptions wireSpawnExceptions(final SmpPlugin plugin) {
        final SpawnExceptions exceptions = new SpawnExceptions(plugin, plugin.clock(), plugin.sounds);
        plugin.getServer().getPluginManager().registerEvents(exceptions, plugin);
        exceptions.start(plugin.spawnExceptionsSettings.get());
        PaperScheduler.of(plugin).onMainEvery(Duration.ofSeconds(1), Duration.ofSeconds(1), exceptions::tick);
        return exceptions;
    }

    /** Starts the in-game capture listener for spawn exception doors and trapdoors. */
    static SpawnExceptionCapture wireSpawnExceptionCapture(
            final SmpPlugin plugin, final CaptureSessions captureSessions, final CaptureFeedback feedback) {
        final SpawnExceptionCapture capture = new SpawnExceptionCapture(
                plugin,
                Inbox.over(plugin.pool(), SmpRequest.TABLE),
                () -> plugin.protectedAreas,
                captureSessions,
                feedback);
        plugin.getServer().getPluginManager().registerEvents(capture, plugin);
        return capture;
    }

    static void restoreGravesAndRegisterWorld(final SmpPlugin plugin, final SpawnExceptions exceptions) {
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
                                plugin.sounds,
                                block -> isWheelBlock(plugin, block),
                                block -> plugin.rails != null
                                        && plugin.rails.networks().isStation(block),
                                block -> plugin.rails != null
                                        && plugin.rails.networks().isNetworkRail(block),
                                exceptions::isAllowed),
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

    private static boolean isWheelBlock(final SmpPlugin plugin, final org.bukkit.block.Block block) {
        final String world = block.getWorld().getName();
        final int x = block.getX();
        final int y = block.getY();
        final int z = block.getZ();
        for (final WheelBlockSpec entry : plugin.wheelSettings.get().wheelBlocks()) {
            if (entry.world().equals(world) && entry.x() == x && entry.y() == y && entry.z() == z) {
                return true;
            }
        }
        return false;
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
                player -> plugin.identities().of(player.getUniqueId()).admin(),
                plugin.worlds.nameOf(WorldRole.NORDTAL),
                () -> plugin.borderSettings.get().borderExpansionBlocksPerSecond());
        scheduler.onMainEvery(
                PaperScheduler.TICK,
                PaperScheduler.TICK,
                () -> wall.tick(plugin.getServer().getOnlinePlayers()));
        plugin.getServer().getPluginManager().registerEvents(wall, plugin);
        wall.apply(plugin.customBorderSettings.get(), plugin.getServer().getOnlinePlayers());
        return wall;
    }

    /** Starts the in-game area editor, its listener and its walk every tick. {@code overworld} names the overworld. */
    static AreaEditor wireAreaEditor(
            final SmpPlugin plugin,
            final WorldEffects effects,
            final String overworld,
            final CaptureSessions captureSessions) {
        final AreaEditor editor = new AreaEditor(
                plugin,
                Inbox.over(plugin.pool(), SmpRequest.TABLE),
                effects,
                (player, document) -> Lines.joined(plugin.renderer()
                        .format(plugin.identities().languageOf(player.getUniqueId()), Sent.of(document))),
                overworld::equals,
                captureSessions);
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        scheduler.onMainEvery(
                PaperScheduler.TICK,
                PaperScheduler.TICK,
                () -> editor.tick(plugin.getServer().getCurrentTick()));
        plugin.getServer().getPluginManager().registerEvents(editor, plugin);
        return editor;
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
        menus.examples(plugin::example);
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

    /** Builds the central custom items registry and registers its draft preview. */
    static CustomItems wireCustomItems(final SmpPlugin plugin) {
        final RailNetworks rails =
                Objects.requireNonNull(plugin.rails, "rails start before items").networks();
        final CustomItems registry = new CustomItems(java.util.List.of(
                new CustomItems.Entry("station_rail", rails::stationRailItem),
                new CustomItems.Entry("tunnel_miner_book", TunnelMinerItems::enchantedBook)));
        ItemsMenu.examples(plugin::example, registry);
        return registry;
    }

    /** Opens the paginated Nordtal custom items menu for an in-game admin. */
    static int openAdminItems(final SmpPlugin plugin, final org.bukkit.command.CommandSender sender) {
        if (sender instanceof final org.bukkit.entity.Player player && plugin.customItems != null) {
            final GameDocument document = new GameDocument(
                    plugin.renderer(),
                    plugin.documents().applied(ItemsMenu.DOCUMENT),
                    Viewer.of(plugin.identities().languageOf(player.getUniqueId())),
                    new DocumentSpec.None());
            ItemsMenu.open(player, plugin.customItems, document);
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /** Wires Tunnel Miner with spawn, custom border, active duel arena and station rail protections. */
    static TunnelMiner wireTunnelMiner(final SmpPlugin plugin) {
        final RailNetworks rails = Objects.requireNonNull(plugin.rails, "rails start before tunnel miner")
                .networks();
        final TunnelMiner miner = new TunnelMiner(
                plugin,
                () -> plugin.tunnelMinerSettings.get().timeFactor(),
                tunnelProtections(plugin, rails),
                rails::notifyRailChanged,
                rails::isStation,
                rails::removeStationForTunnel);
        plugin.getServer().getPluginManager().registerEvents(miner, plugin);
        PaperScheduler.of(plugin)
                .onMainEvery(
                        PaperScheduler.TICK,
                        PaperScheduler.TICK,
                        () -> miner.tick(plugin.getServer().getOnlinePlayers()));
        return miner;
    }

    private static TunnelProtections tunnelProtections(final SmpPlugin plugin, final RailNetworks rails) {
        final BorderWall border = Objects.requireNonNull(plugin.borderWall, "border starts before tunnel miner");
        final Duels duels = Objects.requireNonNull(plugin.duels, "duels start before tunnel miner");
        return new TunnelProtections() {
            @Override
            public boolean check(
                    final org.bukkit.entity.Player player,
                    final org.bukkit.World world,
                    final java.util.Collection<BlockPos> footprint,
                    final boolean notifyPlayer) {
                final boolean creativeAdmin = player.getGameMode() == org.bukkit.GameMode.CREATIVE
                        && plugin.identities().of(player.getUniqueId()).admin();
                for (final BlockPos pos : footprint) {
                    final org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    final DocumentSpec<DocumentSpec.None> refusal =
                            refusalForBlock(plugin, rails, border, duels, player, block, creativeAdmin);
                    if (refusal != null) {
                        if (notifyPlayer) {
                            refuse(plugin, player, refusal);
                        }
                        return false;
                    }
                }
                return true;
            }
        };
    }

    private static @org.jspecify.annotations.Nullable DocumentSpec<DocumentSpec.None> refusalForBlock(
            final SmpPlugin plugin,
            final RailNetworks rails,
            final BorderWall border,
            final Duels duels,
            final org.bukkit.entity.Player player,
            final org.bukkit.block.Block block,
            final boolean creativeAdmin) {
        if (duels.isActiveArenaBlock(block)) {
            return ProtectionListener.DENIED;
        }
        if (!creativeAdmin
                && plugin.protectedAreas.containsBlock(
                        block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
            return ProtectionListener.DENIED;
        }
        if (border.blocksEdit(player, block)) {
            return BorderWall.LIMIT;
        }
        return rails.stationBreakRefusal(
                block, player.getUniqueId(), plugin.identities().of(player).admin());
    }

    private static void refuse(
            final SmpPlugin plugin,
            final org.bukkit.entity.Player player,
            final DocumentSpec<DocumentSpec.None> document) {
        player.sendActionBar(Lines.joined(
                plugin.renderer().format(plugin.identities().languageOf(player.getUniqueId()), Sent.of(document))));
        plugin.sounds.play(player, Feedback.REFUSED);
    }
}
