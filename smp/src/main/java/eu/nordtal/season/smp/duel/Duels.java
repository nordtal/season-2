package eu.nordtal.season.smp.duel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.dialog.Dialogs;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.TakenItems;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.config.DuelKitSpec;
import eu.nordtal.season.smp.config.DuelLoadoutItemSpec;
import eu.nordtal.season.smp.config.DuelsSpec;
import eu.nordtal.season.smp.config.SpotSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.port.Arenas;
import eu.nordtal.season.smp.region.ConfigAreas;
import eu.nordtal.season.smp.region.PolygonArea;
import eu.nordtal.season.smp.region.PolygonAreas;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * The one duel at a time, fought in the arena at spawn with the kit both fighters agreed on in the {@link Negotiation}.
 *
 * The arena's blocks are copied before the fight and placed back after it, however it ends.
 */
public final class Duels implements Arenas {

    /** Why a duel cannot start. */
    enum Blocked {
        ARENA_BUSY,
        ARENA_NOT_READY
    }

    enum Outcome {
        WON,
        LOST,
        INTERRUPTED
    }

    /** An interrupted duel moves no aura and shows no title. */
    record Result(Outcome outcome, @Example("25") int aura) {}

    /** The beat at zero seconds is the start of the fight. */
    record Countdown(@Example("5") long seconds) {}

    static final DocumentSpec<Countdown> COUNTDOWN_LINE = DocumentSpec.game(
                    "smp", "duel-countdown", "Duels", DocumentSpec.Shape.CHAT, Countdown.class)
            .titled("Duel countdown");
    static final DocumentSpec<Result> RESULT = DocumentSpec.game(
                    "smp", "duel-result", "Duels", DocumentSpec.Shape.CHAT, Result.class)
            .titled("Duel result");
    static final DocumentSpec<Result> RESULT_TITLE = DocumentSpec.game(
                    "smp", "duel-result-title", "Duels", DocumentSpec.Shape.TITLE, Result.class)
            .titled("Duel result title");

    private static final int COUNTDOWN_SECONDS = 3;

    private static final CountdownPlan COUNTDOWN = CountdownPlan.at().everySecondFrom(COUNTDOWN_SECONDS);

    private static final Title.Times OUTCOME =
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(600));

    private final Plugin plugin;
    private final AuraDao aura;
    private final DuelsSpec config;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final Clock clock;
    private final ArenaCopy copy;
    private final FighterStateStore fighterStates;
    private final Negotiation negotiation;

    private @Nullable ActiveDuel active;

    /** {@link #respawned} restores the saved state of a fighter who died in the arena. */
    private final Map<UUID, SavedState> pending = new HashMap<>();

    /** Teleports started by the duel itself, only while their event is running. */
    private final Set<UUID> ownTeleports = new HashSet<>();

    /** Since when a non-fighter has been inside the arena without being pushed clear yet. */
    private final Map<UUID, Long> arenaIntruderSince = new HashMap<>();

    private final NoticeThrottle arenaPushSound = new NoticeThrottle(500);

    private static final double ARENA_PUSH = 1.2;
    private static final double ARENA_LIFT = 0.35;
    private static final long ARENA_EJECT_MILLIS = 1_000;

    public Duels(
            final Plugin plugin,
            final AuraDao aura,
            final DuelsSpec config,
            final Identities identities,
            final MessageRenderer renderer,
            final AppliedDocuments documents,
            final Dialogs dialogs,
            final SmpSounds sounds,
            final WorldEffects effects,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.aura = aura;
        this.config = config;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        this.effects = effects;
        this.copy = new ArenaCopy(plugin);
        this.fighterStates = new FighterStateStore(plugin);
        this.negotiation =
                new Negotiation(plugin, this, config, identities, renderer, documents, dialogs, sounds, clock);
    }

    /** {@code discordIds} is read at the start, because the identity cache forgets a player on quit. */
    private record ActiveDuel(
            UUID first,
            UUID second,
            String kit,
            PolygonAreas arena,
            Map<UUID, SavedState> saved,
            Map<UUID, String> discordIds,
            Set<UUID> projectiles,
            long startedAt) {

        UUID opponentOf(final UUID player) {
            return player.equals(first) ? second : first;
        }

        boolean fights(final UUID player) {
            return player.equals(first) || player.equals(second);
        }
    }

    @Override
    public boolean isInArena(final Player player) {
        return active != null && active.fights(player.getUniqueId());
    }

    @Override
    public boolean allowsDuelDamage(final Player victim, final @Nullable Entity damager) {
        final ActiveDuel duel = active;
        if (duel == null || !duel.fights(victim.getUniqueId())) {
            return false;
        }
        final Location at = Objects.requireNonNull(victim.getLocation());
        if (!duel.arena().containsBlock(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return false;
        }
        if (damager == null) {
            return true;
        }
        final Entity attacker = damager instanceof final Projectile projectile
                        && projectile.getShooter() instanceof final Entity shooter
                ? shooter
                : damager;
        return !(attacker instanceof final Player other) || duel.fights(other.getUniqueId());
    }

    @Override
    public boolean isActiveArenaBlock(final Block block) {
        final ActiveDuel duel = active;
        return duel != null
                && duel.arena().containsBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Returns whether the player's advancement criteria are refused now. Main thread only. */
    public boolean refusesAdvancements(final Player player) {
        final UUID id = player.getUniqueId();
        return refusesAdvancements(active != null && active.fights(id), pending.containsKey(id));
    }

    /** Refuses a player's advancement criteria while they fight, and after a death in the arena until the respawn. */
    static boolean refusesAdvancements(final boolean fights, final boolean awaitsRestore) {
        return fights || awaitsRestore;
    }

    /** A fighter builds in the arena during the duel, because the arena is restored afterwards. */
    @Override
    public boolean buildsInArena(final Player player, final Block block) {
        final ActiveDuel duel = active;
        return duel != null
                && duel.fights(player.getUniqueId())
                && duel.arena().containsBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    boolean fighting() {
        return active != null;
    }

    /** A placed kit block outside the arena would survive the restore. */
    public boolean refusesPlacement(final Player player, final Block block) {
        final ActiveDuel duel = active;
        final boolean fights = duel != null && duel.fights(player.getUniqueId());
        final boolean inside = duel != null
                && duel.arena().containsBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        return DuelContainment.refusesBlockPlacement(fights, inside);
    }

    /** Places back a copy of the arena that a crash left behind. Main thread, once the worlds are loaded. */
    public void restoreLeftover() {
        copy.restore();
    }

    /** Keeps non-fighters out of the arena even when standing still or after a save. Main thread, every tick. */
    public void tick(final Collection<? extends Player> players) {
        final PolygonAreas currentArena = currentArena();
        if (currentArena.isEmpty()) {
            arenaIntruderSince.clear();
            return;
        }
        final ActiveDuel duel = active;
        final long now = clock.millis();
        for (final Player player : players) {
            final UUID id = player.getUniqueId();
            final boolean fights = duel != null && duel.fights(id);
            final Location at = Objects.requireNonNull(player.getLocation());
            final Optional<PolygonArea> inside =
                    currentArena.atPoint(at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
            if (!DuelContainment.pushesFromArena(
                    inside.isPresent(),
                    fights,
                    player.getGameMode(),
                    identities.of(id).admin())) {
                arenaIntruderSince.remove(id);
                continue;
            }
            final long since = arenaIntruderSince.computeIfAbsent(id, ignored -> now);
            if (now - since >= ARENA_EJECT_MILLIS) {
                arenaIntruderSince.remove(id);
                player.teleport(outsideLocation(at, inside.get()));
            } else {
                pushOutOfArena(player, inside.get(), at);
            }
        }
    }

    private PolygonAreas currentArena() {
        final ActiveDuel duel = active;
        return duel != null ? duel.arena() : ConfigAreas.ofDuel(config.arena());
    }

    public void moved(final PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.hasChangedBlock()) {
            negotiation.moved(event);
        }
        final ActiveDuel duel = active;
        final Player player = event.getPlayer();
        final Location to = event.getTo();
        final World world = to.getWorld();
        final boolean fights = duel != null && duel.fights(player.getUniqueId());
        final PolygonAreas currentArena = currentArena();
        final Optional<PolygonArea> entered = world == null
                ? Optional.empty()
                : currentArena.atPoint(world.getName(), to.getX(), to.getY(), to.getZ());
        if (DuelContainment.pushesFromArena(
                entered.isPresent(),
                fights,
                player.getGameMode(),
                identities.of(player.getUniqueId()).admin())) {
            if (event instanceof PlayerTeleportEvent) {
                event.setCancelled(true);
                return;
            }
            final Location from = event.getFrom();
            final boolean wasInside = from.getWorld() != null
                    && currentArena.containsPoint(from.getWorld().getName(), from.getX(), from.getY(), from.getZ());
            if (!wasInside) {
                event.setCancelled(true);
            }
            pushOutOfArena(player, entered.get(), to);
            return;
        }
        final boolean inside = duel != null
                && world != null
                && duel.arena().containsBlock(world.getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        final boolean ownTeleport = event instanceof PlayerTeleportEvent && ownTeleports.contains(player.getUniqueId());
        if (!DuelContainment.leavesArena(fights, ownTeleport, inside)) {
            return;
        }
        event.setCancelled(true);
        PaperScheduler.of(plugin).onMain(() -> decide(player));
    }

    private void pushOutOfArena(final Player player, final PolygonArea area, final Location at) {
        final double[] away = area.outward(at.getX(), at.getZ());
        player.setVelocity(new Vector(away[0] * ARENA_PUSH, ARENA_LIFT, away[1] * ARENA_PUSH));
        if (arenaPushSound.allows(player.getUniqueId(), clock.millis())) {
            sounds.play(player, Feedback.REFUSED);
        }
    }

    private static Location outsideLocation(final Location at, final PolygonArea area) {
        final double[] out = area.outsidePoint(at.getX(), at.getZ(), 1.2);
        final Location target = at.clone();
        target.setX(out[0]);
        target.setZ(out[1]);
        final BoundingBox body = new BoundingBox(
                out[0] - 0.3, target.getY(), out[1] - 0.3, out[0] + 0.3, target.getY() + 1.8, out[1] + 0.3);
        while (target.getY() < at.getWorld().getMaxHeight() && at.getWorld().hasCollisionsIn(body)) {
            target.add(0, 1, 0);
            body.shift(0, 1, 0);
        }
        return target;
    }

    /** Tracks projectiles so none survives the duel or can be collected by another player. */
    public void launched(final Projectile projectile) {
        final ActiveDuel duel = active;
        if (duel == null || !(projectile.getShooter() instanceof final Player shooter)) {
            return;
        }
        if (!duel.fights(shooter.getUniqueId())) {
            return;
        }
        duel.projectiles().add(projectile.getUniqueId());
        if (projectile instanceof final AbstractArrow arrow) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }
    }

    /** A player sent a chat line or a command. */
    public void typed(final Player player) {
        negotiation.typed(player);
    }

    public void quit(final Player player) {
        arenaIntruderSince.remove(player.getUniqueId());
        arenaPushSound.forget(player.getUniqueId());
        negotiation.quit(player);
        if (isInArena(player)) {
            decide(player);
        }
    }

    /** What {@link #joined} does when a player logs in. */
    enum JoinRecovery {
        /** Nothing to restore: the player fights, already awaits respawn, or has no persisted state. */
        SKIP,
        /** The fighter logged in dead: park the saved state until respawn and trigger respawn. */
        PARK_PENDING,
        /** The fighter logged in alive: restore once at {@code duels.exit} and delete the persisted file. */
        RESTORE_NOW;

        static JoinRecovery of(
                final boolean inArena, final boolean pending, final boolean dead, final boolean hasStored) {
            if (inArena || pending || !hasStored) {
                return SKIP;
            }
            return dead ? PARK_PENDING : RESTORE_NOW;
        }
    }

    /** Restores a fighter whose duel was cut short by a crash, once, at {@code duels.exit}. */
    public void joined(final Player player) {
        recoverOnJoin(
                player.getUniqueId(),
                isInArena(player),
                player.isDead(),
                fighterStates,
                pending,
                stored -> SavedState.fromStored(stored, plugin.getLogger()),
                state -> state.restore(player, exit()),
                () -> player.spigot().respawn());
    }

    static <T> JoinRecovery recoverOnJoin(
            final UUID playerId,
            final boolean inArena,
            final boolean dead,
            final FighterStateStore store,
            final Map<UUID, T> pending,
            final Function<FighterStateStore.StoredState, T> decode,
            final Consumer<T> restoreAtExit,
            final Runnable triggerRespawn) {
        final boolean alreadyPending = pending.containsKey(playerId);
        final FighterStateStore.@Nullable StoredState stored = inArena || alreadyPending ? null : store.load(playerId);
        final JoinRecovery action = JoinRecovery.of(inArena, alreadyPending, dead, stored != null);
        switch (action) {
            case SKIP -> {}
            case PARK_PENDING -> {
                pending.put(playerId, decode.apply(Objects.requireNonNull(stored)));
                triggerRespawn.run();
            }
            case RESTORE_NOW -> {
                restoreAtExit.accept(decode.apply(Objects.requireNonNull(stored)));
                store.delete(playerId);
            }
        }
        return action;
    }

    /** The inventory is restored one tick later, after the respawn has written its own. */
    public void respawned(final org.bukkit.event.player.PlayerRespawnEvent event) {
        final SavedState state = pending.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        final Location where = exit();
        if (where != null) {
            event.setRespawnLocation(where);
        }
        PaperScheduler.of(plugin).onMain(() -> {
            final Player player = Bukkit.getPlayer(event.getPlayer().getUniqueId());
            if (player != null) {
                state.restore(player, exit());
                fighterStates.delete(player.getUniqueId());
            }
        });
    }

    /**
     * Called by the negotiation's countdown, never from a move event: Bukkit would undo the teleport.
     *
     * @return why no duel could start, with the two players left where they stand, or {@code null} once it started
     */
    @Nullable
    Blocked begin(final Player first, final Player second, final DuelKitSpec kit) {
        if (active != null) {
            return Blocked.ARENA_BUSY;
        }
        final PolygonAreas arena = ConfigAreas.ofDuel(config.arena());
        final Location startFirst = position(config.fighterStartFirst());
        final Location startSecond = position(config.fighterStartSecond());
        if (arena.isEmpty() || startFirst == null || startSecond == null) {
            plugin.getLogger().warning("duel world or arena not ready, duel refused");
            return Blocked.ARENA_NOT_READY;
        }
        if (!copy.save(arena)) {
            return Blocked.ARENA_NOT_READY;
        }

        final SavedState firstState = SavedState.of(first);
        final SavedState secondState = SavedState.of(second);
        if (!persistStates(first.getUniqueId(), firstState, second.getUniqueId(), secondState)) {
            copy.restore();
            return Blocked.ARENA_NOT_READY;
        }

        final Map<UUID, SavedState> saved = new HashMap<>();
        saved.put(first.getUniqueId(), firstState);
        saved.put(second.getUniqueId(), secondState);

        final Map<UUID, String> discordIds = new HashMap<>();
        identities.discordIdOf(first.getUniqueId()).ifPresent(id -> discordIds.put(first.getUniqueId(), id.value()));
        identities.discordIdOf(second.getUniqueId()).ifPresent(id -> discordIds.put(second.getUniqueId(), id.value()));

        final ActiveDuel duel = new ActiveDuel(
                first.getUniqueId(),
                second.getUniqueId(),
                kit.name(),
                arena,
                saved,
                discordIds,
                new HashSet<>(),
                clock.millis());
        active = duel;

        if (!enter(first, startFirst, kit.loadout()) || !enter(second, startSecond, kit.loadout())) {
            abort(duel);
            return null;
        }
        countdown(duel);
        return null;
    }

    private boolean persistStates(
            final UUID firstId, final SavedState firstState, final UUID secondId, final SavedState secondState) {
        if (!fighterStates.save(firstId, firstState.toStored())) {
            return false;
        }
        if (!fighterStates.save(secondId, secondState.toStored())) {
            fighterStates.delete(firstId);
            return false;
        }
        return true;
    }

    private static @Nullable Location position(final SpotSpec spec) {
        final World world = Bukkit.getWorld(spec.world());
        return world == null ? null : new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), spec.pitch());
    }

    /** Beside the negotiation area; null falls back to where the fighter stood before the duel. */
    private @Nullable Location exit() {
        return position(config.exit());
    }

    /** Books no stake. */
    private void abort(final ActiveDuel duel) {
        active = null;
        removeProjectiles(duel);
        restore(duel, duel.first(), null);
        restore(duel, duel.second(), null);
        copy.restore();
    }

    /** Returns false when the player is not in the arena. */
    private boolean enter(final Player player, final Location at, final List<DuelLoadoutItemSpec> loadout) {
        SavedState.clear(player);
        ownTeleports.add(player.getUniqueId());
        try {
            if (!player.teleport(at)) {
                plugin.getLogger().warning("participant teleport failed, duel cancelled: player=" + player.getName());
                return false;
            }
        } finally {
            ownTeleports.remove(player.getUniqueId());
        }
        player.setGameMode(GameMode.ADVENTURE);
        giveLoadout(player, loadout);
        sounds.play(player, Feedback.TRAVEL);
        effects.arenaEntered(at);
        return true;
    }

    private void giveLoadout(final Player player, final List<DuelLoadoutItemSpec> loadout) {
        for (final DuelLoadoutItemSpec entry : loadout) {
            final ItemStack one = TakenItems.one(entry.item(), entry.data());
            if (one == null) {
                plugin.getLogger().warning("duel loadout item unreadable, item left out: item=" + entry.item());
                continue;
            }
            final ItemStack stack = one.asQuantity(Math.max(1, entry.amount()));
            if (!equipIfArmour(player, stack.getType(), stack)) {
                player.getInventory().addItem(stack);
            }
        }
    }

    private static boolean equipIfArmour(final Player player, final Material material, final ItemStack stack) {
        final String name = material.name();
        if (name.endsWith("_HELMET")) {
            player.getInventory().setHelmet(stack);
        } else if (name.endsWith("_CHESTPLATE")) {
            player.getInventory().setChestplate(stack);
        } else if (name.endsWith("_LEGGINGS")) {
            player.getInventory().setLeggings(stack);
        } else if (name.endsWith("_BOOTS")) {
            player.getInventory().setBoots(stack);
        } else {
            return false;
        }
        return true;
    }

    /** The last beat is the start of the fight. */
    private void countdown(final ActiveDuel duel) {
        for (final CountdownPlan.Beat<Sent<Countdown>> beat : COUNTDOWN.beats(
                Duration.ofSeconds(COUNTDOWN_SECONDS),
                seconds -> COUNTDOWN_LINE.with(new Countdown(seconds)),
                COUNTDOWN_LINE.with(new Countdown(0)))) {
            PaperScheduler.of(plugin).onMainAfter(beat.delay(), () -> say(duel, beat));
        }
    }

    private void say(final ActiveDuel duel, final CountdownPlan.Beat<Sent<Countdown>> beat) {
        if (!duel.equals(active)) {
            return;
        }
        forBoth(duel, player -> {
            player.setGameMode(beat.seconds() > 0 ? GameMode.ADVENTURE : GameMode.SURVIVAL);
            player.sendMessage(renderer.format(identities.languageOf(player.getUniqueId()), beat.said()));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        });
    }

    public void decide(final Player loser) {
        final ActiveDuel duel = active;
        if (duel == null || !duel.fights(loser.getUniqueId())) {
            return;
        }
        final UUID winnerId = duel.opponentOf(loser.getUniqueId());
        finish(duel, winnerId, loser.getUniqueId());
    }

    private void finish(final ActiveDuel duel, final UUID winnerId, final UUID loserId) {
        active = null;
        removeProjectiles(duel);
        restore(duel, winnerId, Feedback.BIG_SUCCESS);
        restore(duel, loserId, Feedback.LOSS);
        copy.restore();
        book(winnerId, loserId, duel);
    }

    /** A null {@code feedback} means that {@code stop()} ends the duel. */
    private void restore(final ActiveDuel duel, final UUID playerId, final @Nullable Feedback feedback) {
        final SavedState state = duel.saved().get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || state == null) {
            return;
        }
        if (player.isDead()) {
            pending.put(playerId, state);
            player.spigot().respawn();
        } else {
            state.restore(player, exit());
            fighterStates.delete(playerId);
        }
        final java.util.Locale locale = identities.languageOf(playerId);
        final Result result = new Result(
                feedback == null ? Outcome.INTERRUPTED : feedback == Feedback.BIG_SUCCESS ? Outcome.WON : Outcome.LOST,
                config.duelStake());
        player.sendMessage(renderer.format(locale, RESULT.with(result)));
        if (feedback != null) {
            player.showTitle(renderer.title(locale, RESULT_TITLE.with(result), OUTCOME));
        }
        if (feedback != null) {
            sounds.play(player, feedback);
        }
    }

    private static void removeProjectiles(final ActiveDuel duel) {
        for (final UUID id : duel.projectiles()) {
            final Entity projectile = Bukkit.getEntity(id);
            if (projectile != null) {
                projectile.remove();
            }
        }
        duel.projectiles().clear();
    }

    private void book(final UUID winnerId, final UUID loserId, final ActiveDuel duel) {
        // A disconnect has already cleared the identity cache.
        final String winner = duel.discordIds().get(winnerId);
        final String loser = duel.discordIds().get(loserId);
        if (winner == null || loser == null) {
            return;
        }
        final int stake = config.duelStake();
        final String kit = duel.kit();

        PaperScheduler.of(plugin).execute(() -> {
            aura.addAura(DiscordId.of(winner), stake, new AuraCause.Duel(true, kit));
            aura.addAura(DiscordId.of(loser), -stake, new AuraCause.Duel(false, kit));
        });
    }

    public void stop() {
        negotiation.stop();
        final ActiveDuel duel = active;
        if (duel != null) {
            active = null;
            removeProjectiles(duel);
            restore(duel, duel.first(), null);
            restore(duel, duel.second(), null);
        }
        copy.restore();
    }

    private void forBoth(final ActiveDuel duel, final java.util.function.Consumer<Player> action) {
        for (final UUID id : List.of(duel.first(), duel.second())) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }
}
