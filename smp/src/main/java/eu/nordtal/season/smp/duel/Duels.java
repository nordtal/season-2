package eu.nordtal.season.smp.duel;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.CountdownPlan;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.SmpMessages;
import eu.nordtal.season.smp.aura.AuraCause;
import eu.nordtal.season.smp.aura.AuraDao;
import eu.nordtal.season.smp.aura.DuelType;
import eu.nordtal.season.smp.config.SmpSpec;
import eu.nordtal.season.smp.config.WheelPrizeSpec;
import eu.nordtal.season.smp.feedback.SmpSounds;
import eu.nordtal.season.smp.feedback.WorldEffects;
import eu.nordtal.season.smp.port.Arenas;
import eu.nordtal.season.smp.world.WorldRole;
import eu.nordtal.season.smp.world.Worlds;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

public final class Duels implements Arenas {

    private static final int COUNTDOWN_SECONDS = 3;

    private static final CountdownPlan COUNTDOWN = CountdownPlan.at().everySecondFrom(COUNTDOWN_SECONDS);

    private final Plugin plugin;
    private final AuraDao aura;
    private final SmpSpec config;
    private final Worlds worlds;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final ArenaSlots slots;

    private final Map<DuelType, UUID> waiting = new HashMap<>();

    private final List<Queued> queue = new ArrayList<>();

    private final Map<UUID, ActiveDuel> byPlayer = new HashMap<>();

    /** A fighter in {@code settled} must step off the platform before the next duel. */
    private final java.util.Set<UUID> settled = new java.util.HashSet<>();

    /** {@link #respawned} restores the saved state of a fighter who died in the arena. */
    private final Map<UUID, SavedState> pending = new HashMap<>();

    private static final Title.Times OUTCOME = Title.Times.times(
            java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(600));

    private @Nullable Location spawn() {
        return worlds.world(WorldRole.NORDTAL)
                .map(world -> eu.nordtal.season.smp.world.LandingSite.safeAt(world, world.getSpawnLocation()))
                .orElse(null);
    }

    private final Map<Integer, List<Location>> placed = new HashMap<>();

    private final Clock clock;

    public Duels(
            final Plugin plugin,
            final AuraDao aura,
            final SmpSpec config,
            final Worlds worlds,
            final Identities identities,
            final MessageRenderer renderer,
            final SmpSounds sounds,
            final WorldEffects effects,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.aura = aura;
        this.config = config;
        this.worlds = worlds;
        this.identities = identities;
        this.renderer = renderer;
        this.sounds = sounds;
        this.effects = effects;
        this.slots = new ArenaSlots(config.concurrentDuelLimit(), config.duelArenaBaseY(), config.duelArenaSpacing());
    }

    private record Queued(UUID first, UUID second, DuelType type) {}

    /** {@code discordIds} is read at the start, because the identity cache forgets a player on quit. */
    private record ActiveDuel(
            UUID first,
            UUID second,
            DuelType type,
            int slot,
            Map<UUID, SavedState> saved,
            Map<UUID, String> discordIds,
            long startedAt) {

        UUID opponentOf(final UUID player) {
            return player.equals(first) ? second : first;
        }
    }

    @Override
    public boolean isInArena(final Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    public void steppedOn(final Player player, final DuelType type) {
        if (isInArena(player) || settled.contains(player.getUniqueId())) {
            return;
        }
        final UUID other = waiting.get(type);
        if (other != null && other.equals(player.getUniqueId())) {
            return;
        }
        if (other == null) {
            waiting.put(type, player.getUniqueId());
            tell(player, MESSAGES.smp().duel().waiting(), Feedback.SELECT);
            return;
        }
        final Player opponent = Bukkit.getPlayer(other);
        if (opponent == null) {
            waiting.put(type, player.getUniqueId());
            return;
        }
        waiting.remove(type);
        // Bukkit applies the event's target after the handlers return, which undoes a teleport made in them.
        final java.util.UUID firstId = opponent.getUniqueId();
        final java.util.UUID secondId = player.getUniqueId();
        PaperScheduler.of(plugin).onMain(() -> {
            final Player first = Bukkit.getPlayer(firstId);
            final Player second = Bukkit.getPlayer(secondId);
            if (first != null && second != null) {
                begin(first, second, type);
            }
        });
    }

    /** The inventory is restored one tick later, after the respawn has written its own. */
    public void respawned(final org.bukkit.event.player.PlayerRespawnEvent event) {
        final SavedState state = pending.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        final Location where = spawn();
        if (where != null) {
            event.setRespawnLocation(where);
        }
        PaperScheduler.of(plugin).onMain(() -> {
            final Player player = Bukkit.getPlayer(event.getPlayer().getUniqueId());
            if (player != null) {
                state.restore(player, spawn());
            }
        });
    }

    public void steppedOff(final Player player) {
        waiting.entrySet().removeIf(entry -> entry.getValue().equals(player.getUniqueId()));
        settled.remove(player.getUniqueId());
    }

    private void begin(final Player first, final Player second, final DuelType type) {
        final Optional<Integer> slot = slots.claim();
        if (slot.isEmpty()) {
            queue.add(new Queued(first.getUniqueId(), second.getUniqueId(), type));
            tell(first, MESSAGES.smp().duel().queued());
            tell(second, MESSAGES.smp().duel().queued());
            return;
        }

        final World world = worlds.world(WorldRole.NORDTAL).orElse(null);
        if (world == null) {
            slots.release(slot.get());
            return;
        }

        final Location centre =
                new Location(world, config.borderCentreX() + 0.5, slots.yOf(slot.get()), config.borderCentreZ() + 0.5);
        build(slot.get(), centre);

        final Map<UUID, SavedState> saved = new HashMap<>();
        saved.put(first.getUniqueId(), SavedState.of(first));
        saved.put(second.getUniqueId(), SavedState.of(second));

        final Map<UUID, String> discordIds = new HashMap<>();
        identities.discordIdOf(first.getUniqueId()).ifPresent(id -> discordIds.put(first.getUniqueId(), id.value()));
        identities.discordIdOf(second.getUniqueId()).ifPresent(id -> discordIds.put(second.getUniqueId(), id.value()));

        final ActiveDuel duel = new ActiveDuel(
                first.getUniqueId(), second.getUniqueId(), type, slot.get(), saved, discordIds, clock.millis());
        byPlayer.put(first.getUniqueId(), duel);
        byPlayer.put(second.getUniqueId(), duel);

        final int radius = config.duelArenaRadius();
        if (!enter(first, centre.clone().add(-radius + 1.5, 1, 0), type)
                || !enter(second, centre.clone().add(radius - 1.5, 1, 0), type)) {
            abort(duel);
            return;
        }
        countdown(duel);
    }

    /** Books no stake. */
    private void abort(final ActiveDuel duel) {
        byPlayer.remove(duel.first());
        byPlayer.remove(duel.second());
        restore(duel, duel.first(), null);
        restore(duel, duel.second(), null);
        teardown(duel.slot());
        slots.release(duel.slot());
    }

    /** Returns false when the player is not in the arena. */
    private boolean enter(final Player player, final Location at, final DuelType type) {
        SavedState.clear(player);
        if (!player.teleport(at)) {
            plugin.getLogger()
                    .warning(player.getName() + " could not be moved into the duel arena; "
                            + "the duel is called off rather than fought outside it");
            return false;
        }
        player.setGameMode(GameMode.ADVENTURE);
        giveLoadout(player, type);
        sounds.play(player, Feedback.TRAVEL);
        effects.arenaEntered(at);
        return true;
    }

    private void giveLoadout(final Player player, final DuelType type) {
        final List<WheelPrizeSpec> loadout =
                type == DuelType.SWORD ? config.duelLoadoutSword() : config.duelLoadoutBow();

        for (final WheelPrizeSpec entry : loadout) {
            final Material material = GameKeys.material(entry.item()).orElse(null);
            if (material == null) {
                plugin.getLogger()
                        .warning("a duel loadout names '" + entry.item()
                                + "', which is not a material - that piece is missing from the fight");
                continue;
            }
            final ItemStack stack = new ItemStack(material, Math.max(1, entry.amount()));
            if (!equipIfArmour(player, material, stack)) {
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
        final SmpMessages.Smp.Duel lines = MESSAGES.smp().duel();
        for (final CountdownPlan.Beat<MessageRef> beat :
                COUNTDOWN.beats(Duration.ofSeconds(COUNTDOWN_SECONDS), lines::countdown, lines.go())) {
            PaperScheduler.of(plugin).onMainAfter(beat.delay(), () -> say(duel, beat));
        }
    }

    private void say(final ActiveDuel duel, final CountdownPlan.Beat<MessageRef> beat) {
        if (!duel.equals(byPlayer.get(duel.first()))) {
            return;
        }
        forBoth(duel, player -> {
            player.setGameMode(beat.seconds() > 0 ? GameMode.ADVENTURE : GameMode.SURVIVAL);
            player.sendMessage(renderer.format(identities.languageOf(player.getUniqueId()), beat.said()));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        });
    }

    public void decide(final Player loser) {
        final ActiveDuel duel = byPlayer.get(loser.getUniqueId());
        if (duel == null) {
            return;
        }
        final UUID winnerId = duel.opponentOf(loser.getUniqueId());
        finish(duel, winnerId, loser.getUniqueId());
    }

    private void finish(final ActiveDuel duel, final UUID winnerId, final UUID loserId) {
        byPlayer.remove(duel.first());
        byPlayer.remove(duel.second());
        teardown(duel.slot());
        slots.release(duel.slot());

        restore(duel, winnerId, Feedback.BIG_SUCCESS);
        restore(duel, loserId, Feedback.LOSS);
        book(winnerId, loserId, duel);
        drainQueue();
    }

    /** A null {@code feedback} means that {@code stop()} ends the duel. */
    private void restore(final ActiveDuel duel, final UUID playerId, final @Nullable Feedback feedback) {
        final SavedState state = duel.saved().get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || state == null) {
            return;
        }
        // The restore is a teleport, which counts as a move and would start the next duel.
        settled.add(playerId);
        if (player.isDead()) {
            pending.put(playerId, state);
            player.spigot().respawn();
        } else {
            state.restore(player, spawn());
        }
        final java.util.Locale locale = identities.languageOf(playerId);
        final SmpMessages.Smp.Duel lines = MESSAGES.smp().duel();
        final int stake = config.duelStake();
        player.sendMessage(renderer.format(
                locale,
                feedback == null
                        ? lines.interrupted()
                        : feedback == Feedback.BIG_SUCCESS ? lines.won(stake) : lines.lost(stake)));
        if (feedback != null) {
            final boolean won = feedback == Feedback.BIG_SUCCESS;
            player.showTitle(Title.title(
                    Lines.joined(renderer.format(
                            locale,
                            won
                                    ? lines.wonSection().title()
                                    : lines.lostSection().title())),
                    Lines.joined(renderer.format(
                            locale,
                            won
                                    ? lines.wonSection().subtitle(stake)
                                    : lines.lostSection().subtitle(stake))),
                    OUTCOME));
        }
        if (feedback != null) {
            sounds.play(player, feedback);
        }
    }

    private void book(final UUID winnerId, final UUID loserId, final ActiveDuel duel) {
        // A disconnect has already cleared the identity cache.
        final String winner = duel.discordIds().get(winnerId);
        final String loser = duel.discordIds().get(loserId);
        if (winner == null || loser == null) {
            return;
        }
        final int stake = config.duelStake();
        final DuelType type = duel.type();

        PaperScheduler.of(plugin).execute(() -> {
            aura.addAura(DiscordId.of(winner), stake, new AuraCause.Duel(true, type));
            aura.addAura(DiscordId.of(loser), -stake, new AuraCause.Duel(false, type));
        });
    }

    private void drainQueue() {
        while (!queue.isEmpty() && !slots.isFull()) {
            final Queued pair = queue.remove(0);
            final Player first = Bukkit.getPlayer(pair.first());
            final Player second = Bukkit.getPlayer(pair.second());
            if (first != null && second != null) {
                begin(first, second, pair.type());
            }
        }
    }

    private void build(final int slot, final Location centre) {
        final int radius = config.duelArenaRadius();
        final List<Location> blocks = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                blocks.add(place(centre, x, 0, z));
                blocks.add(place(centre, x, 5, z));
            }
        }
        for (int y = 1; y <= 4; y++) {
            for (int i = -radius; i <= radius; i++) {
                blocks.add(place(centre, i, y, -radius));
                blocks.add(place(centre, i, y, radius));
                blocks.add(place(centre, -radius, y, i));
                blocks.add(place(centre, radius, y, i));
            }
        }
        blocks.removeIf(java.util.Objects::isNull);
        placed.put(slot, blocks);
    }

    private @Nullable Location place(final Location centre, final int dx, final int dy, final int dz) {
        final Location at = centre.clone().add(dx, dy, dz);
        if (!at.getBlock().getType().isAir()) {
            return null;
        }
        at.getBlock().setType(Material.GLASS, false);
        return at;
    }

    private void teardown(final int slot) {
        final List<Location> blocks = placed.remove(slot);
        if (blocks == null) {
            return;
        }
        blocks.forEach(at -> {
            if (at.getBlock().getType() == Material.GLASS) {
                at.getBlock().setType(Material.AIR, false);
            }
        });
    }

    public void stop() {
        List.copyOf(byPlayer.values()).forEach(duel -> {
            byPlayer.remove(duel.first());
            byPlayer.remove(duel.second());
            restore(duel, duel.first(), null);
            restore(duel, duel.second(), null);
            teardown(duel.slot());
            slots.release(duel.slot());
        });
        placed.keySet().forEach(this::teardown);
        placed.clear();
        waiting.clear();
        queue.clear();
    }

    private void forBoth(final ActiveDuel duel, final java.util.function.Consumer<Player> action) {
        for (final UUID id : List.of(duel.first(), duel.second())) {
            final Player player = Bukkit.getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }

    private void tell(final Player player, final MessageRef message) {
        tell(player, message, null);
    }

    private void tell(final Player player, final MessageRef message, final @Nullable Feedback feedback) {
        player.sendMessage(renderer.format(identities.languageOf(player.getUniqueId()), message));
        if (feedback != null) {
            sounds.play(player, feedback);
        }
    }
}
