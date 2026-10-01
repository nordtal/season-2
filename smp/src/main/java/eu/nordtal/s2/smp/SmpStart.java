package eu.nordtal.s2.smp;

import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.papercommon.chat.SystemLines;
import eu.nordtal.s2.smp.announce.Announcer;
import eu.nordtal.s2.smp.aura.DeathPenalty;
import eu.nordtal.s2.smp.board.Boards;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.duel.DuelListener;
import eu.nordtal.s2.smp.duel.Duels;
import eu.nordtal.s2.smp.feedback.SurfaceListener;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.grave.GraveListener;
import eu.nordtal.s2.smp.grave.Graves;
import eu.nordtal.s2.smp.hud.SmpHud;
import eu.nordtal.s2.smp.navigate.NavigateListener;
import eu.nordtal.s2.smp.npc.NpcListener;
import eu.nordtal.s2.smp.npc.NpcProtection;
import eu.nordtal.s2.smp.npc.SpawnNpc;
import eu.nordtal.s2.smp.player.PlayerComposition;
import eu.nordtal.s2.smp.player.PlayerSurfaces;
import eu.nordtal.s2.smp.player.PresenceListener;
import eu.nordtal.s2.smp.progress.AdvancementListener;
import eu.nordtal.s2.smp.progress.GateHolders;
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
import org.bukkit.Bukkit;

/**
 * Everything {@link SmpPlugin#start()} wires up once its refusals have passed.
 *
 * It only returns values: NullAway's initializer check cannot follow a field assignment into another class.
 */
final class SmpStart {

    private SmpStart() {}

    record HudAndAnnouncer(SmpHud hud, Announcer announcer) {}

    static HudAndAnnouncer startHudAndAnnouncer(final SmpPlugin plugin) {
        final SmpHud hud = new SmpHud(
                plugin, plugin.worlds, plugin.season, plugin.navigation, plugin.messages(), plugin.locales());
        hud.start();

        // Discord announcements: one request in the bot's inbox with every language, fire and forget.
        final Announcer announcer = new Announcer(
                Inbox.over(plugin.pool(), BotRequest.TABLE),
                plugin.messages(),
                task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task),
                (message, failure) -> plugin.getLogger().log(java.util.logging.Level.WARNING, message, failure));
        return new HudAndAnnouncer(hud, announcer);
    }

    record Surfaces(WorldEffects effects, PlayerComposition composition, PlayerSurfaces surfaces, Boards boards) {}

    static Surfaces wireEffectsAndSurfaces(final SmpPlugin plugin, final SmpSpec config) {
        // One instance: whichever object stamped a rocket must be the one {@code WorldEffects#onDamage} asks.
        final WorldEffects effects = new WorldEffects(plugin);
        plugin.getServer().getPluginManager().registerEvents(effects, plugin);

        final PlayerComposition composition =
                new PlayerComposition(() -> plugin.prestige, () -> plugin.prestigeColours);
        final PlayerSurfaces surfaces =
                new PlayerSurfaces(plugin, plugin.identities, composition, new MessageRenderer(plugin.messages()));

        final Boards boards = new Boards(plugin, config, plugin.season, plugin.messages(), plugin.locales());
        boards.start();
        return new Surfaces(effects, composition, surfaces, boards);
    }

    record Presence(SystemLines systemLines, BukkitCinematics cinematics, SeasonWelcome welcome) {}

    static Presence wirePresenceInputs(final SmpPlugin plugin, final SmpSpec config, final Surfaces surfaces) {
        final SystemLines systemLines = plugin.systemLines(player ->
                surfaces.composition().chatPrefix(player.getName(), plugin.identities.of(player.getUniqueId())));

        // Paper disables plugins before saving players, so a blindness would otherwise be saved too.
        final BukkitCinematics cinematics = new BukkitCinematics(plugin, plugin.sounds::play);
        plugin.getServer().getPluginManager().registerEvents(cinematics, plugin);
        final SeasonWelcome welcome =
                new SeasonWelcome(plugin, plugin.dao, plugin.identities, cinematics, config, plugin.worlds);
        return new Presence(systemLines, cinematics, welcome);
    }

    static PresenceListener registerPresenceListeners(
            final SmpPlugin plugin, final SmpSpec config, final Surfaces surfaces, final Presence presence) {
        final PresenceListener listener = new PresenceListener(
                plugin, surfaces.surfaces(), presence.systemLines(), presence.welcome()::onLanguageReady);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new NavigateListener(plugin, plugin.dao, plugin.navigation, plugin.identities, plugin.sounds),
                        plugin);
        return listener;
    }

    record Progress(ObjectiveEngine engine, StatisticPoller poller, GateHolders gates) {}

    static Progress wireProgressEngine(final SmpPlugin plugin, final SmpSpec config, final WorldEffects effects) {
        final ObjectiveEngine engine = new ObjectiveEngine(
                plugin,
                plugin.dao,
                () -> plugin.track,
                plugin.season,
                plugin.worlds,
                plugin.identities,
                plugin.messages(),
                plugin.locales(),
                config,
                plugin.sounds,
                effects,
                plugin.announcer);
        final StatisticPoller poller = new StatisticPoller(plugin, () -> plugin.track, engine, plugin.identities);
        poller.start();
        final GateHolders gates = new GateHolders(
                () -> plugin.track,
                GateHolders.Server.running(),
                plugin.identities::discordIdOf,
                task -> Bukkit.getScheduler().runTask(plugin, task),
                task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task),
                engine);
        plugin.getServer().getPluginManager().registerEvents(gates, plugin);
        return new Progress(engine, poller, gates);
    }

    record Activities(DeathPenalty penalty, Wheel wheel, Graves graves, Duels duels) {}

    static Activities wireActivities(final SmpPlugin plugin, final SmpSpec config, final WorldEffects effects) {
        final Graves graves = new Graves(
                plugin,
                plugin.dao,
                plugin.identities,
                plugin.messages(),
                plugin.locales(),
                plugin.sounds,
                effects,
                config,
                plugin.clock());
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
                plugin.messages(),
                plugin.locales(),
                plugin.sounds,
                effects,
                plugin.clock());

        final DeathPenalty penalty = new DeathPenalty(
                config.deathPenalty(), config.deathPenaltyListed(), java.util.Set.copyOf(config.deathCausesListed()));
        final Wheel wheel = new Wheel(
                plugin,
                plugin.dao,
                config,
                plugin.identities,
                plugin.messages(),
                plugin.locales(),
                plugin.sounds,
                plugin.clock());
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
                                plugin.messages(),
                                plugin.locales(),
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
                                plugin.messages(),
                                plugin.locales(),
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
                                plugin.messages(),
                                plugin.locales(),
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
                                regions, plugin.identities, plugin.messages(), plugin.locales(), plugin.sounds),
                        plugin);
        plugin.getServer()
                .getPluginManager()
                .registerEvents(
                        new BalloonListener(
                                balloons,
                                plugin.worlds,
                                plugin.season,
                                () -> plugin.track,
                                plugin.messages(),
                                plugin.locales(),
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
                                plugin,
                                plugin.worlds,
                                plugin.season,
                                plugin.messages(),
                                plugin.locales(),
                                plugin.sounds),
                        plugin);

        // One listener for every menu; the grave inventory has a null holder, hence the predicate.
        plugin.getServer()
                .getPluginManager()
                .registerEvents(new SurfaceListener(plugin.sounds, plugin.graves::isShowingGrave), plugin);
        return balloonDisplay;
    }
}
