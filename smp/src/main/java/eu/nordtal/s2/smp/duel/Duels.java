package eu.nordtal.s2.smp.duel;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Duels: two 3 x 3 platforms at the spawn, an arena that appears above it, and one short fight.
 *
 * <h2>The rules, each of them deliberate</h2>
 * <ul>
 *   <li><b>Separate everything.</b> Inventory, health, effects and experience inside the arena are
 *       the duel's own; the player's real state is untouched. It is also the one place with no
 *       grave, because nothing real was ever at stake.</li>
 *   <li><b>A single fight</b>, no best-of. Short, decisive, and nothing has to survive a restart.</li>
 *   <li><b>Identical loadouts from config.</b> Nobody wins by being richer.</li>
 *   <li><b>Disconnecting is a defeat and the aura is booked.</b> Otherwise logging out is a free
 *       escape from losing.</li>
 *   <li><b>Arenas are visible and spectators are welcome.</b> Duels are the only competition on this
 *       server; hiding them would waste the one thing that gives the tab-list number a story.</li>
 * </ul>
 *
 * <h2>The arena is placed and taken away again</h2>
 * A small glass box, built when the duel starts and removed when it ends - and swept at start, in
 * case a crash left one standing. A schematic replaces the glass eventually; it will change what the
 * arena looks like and nothing about how a duel works.
 */
public final class Duels {

    /** How long the fighters stand still before they may hit each other. */
    private static final int COUNTDOWN_SECONDS = 3;

    private final Plugin plugin;
    private final SmpDao dao;
    private final SmpSpec config;
    private final Worlds worlds;
    private final Identities identities;
    private final Messages messages;
    private final PlayerLocales locales;
    private final ArenaSlots slots;

    /** Who is standing on which platform right now, so a second arrival starts a duel. */
    private final Map<DuelType, UUID> waiting = new HashMap<>();

    /** Pairs still waiting for an arena to free up, in the order they stepped on. */
    private final List<Queued> queue = new ArrayList<>();

    /** player -> the duel they are in. */
    private final Map<UUID, ActiveDuel> byPlayer = new HashMap<>();

    /** Every block this plugin placed for an arena, so a teardown removes exactly those. */
    private final Map<Integer, List<Location>> placed = new HashMap<>();

    public Duels(final Plugin plugin, final SmpDao dao, final SmpSpec config, final Worlds worlds,
                 final Identities identities, final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.worlds = worlds;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.slots = new ArenaSlots(config.concurrentDuelLimit(), config.duelArenaBaseY(),
                config.duelArenaSpacing());
    }

    /** A pair that stepped on while every arena was busy. The type has to travel with them. */
    private record Queued(UUID first, UUID second, DuelType type) {
    }

    /** One running duel. */
    private record ActiveDuel(UUID first, UUID second, DuelType type, int slot,
                              Map<UUID, SavedState> saved, long startedAt) {

        UUID opponentOf(final UUID player) {
            return player.equals(first) ? second : first;
        }
    }

    /** Whether a player is fighting - the grave listener's one question. */
    public boolean isInArena(final Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    // ------------------------------------------------------------------ joining

    /** A player stepped onto a platform. */
    public void steppedOn(final Player player, final DuelType type) {
        if (isInArena(player)) {
            return;
        }
        final UUID other = waiting.get(type);
        if (other == null || other.equals(player.getUniqueId())) {
            waiting.put(type, player.getUniqueId());
            tell(player, "smp.duel.waiting");
            return;
        }
        final Player opponent = Bukkit.getPlayer(other);
        if (opponent == null) {
            waiting.put(type, player.getUniqueId());
            return;
        }
        waiting.remove(type);
        begin(opponent, player, type);
    }

    /** A player stepped off every platform. */
    public void steppedOff(final Player player) {
        waiting.entrySet().removeIf(entry -> entry.getValue().equals(player.getUniqueId()));
    }

    private void begin(final Player first, final Player second, final DuelType type) {
        final Optional<Integer> slot = slots.claim();
        if (slot.isEmpty()) {
            queue.add(new Queued(first.getUniqueId(), second.getUniqueId(), type));
            tell(first, "smp.duel.queued");
            tell(second, "smp.duel.queued");
            return;
        }

        final World world = worlds.world(WorldRole.NORDTAL).orElse(null);
        if (world == null) {
            slots.release(slot.get());
            return;
        }

        final Location centre = new Location(world, config.borderCentreX() + 0.5,
                slots.yOf(slot.get()), config.borderCentreZ() + 0.5);
        build(slot.get(), centre);

        final Map<UUID, SavedState> saved = new HashMap<>();
        saved.put(first.getUniqueId(), SavedState.of(first));
        saved.put(second.getUniqueId(), SavedState.of(second));

        final ActiveDuel duel = new ActiveDuel(first.getUniqueId(), second.getUniqueId(), type,
                slot.get(), saved, System.currentTimeMillis());
        byPlayer.put(first.getUniqueId(), duel);
        byPlayer.put(second.getUniqueId(), duel);

        final int radius = config.duelArenaRadius();
        enter(first, centre.clone().add(-radius + 1.5, 1, 0), type);
        enter(second, centre.clone().add(radius - 1.5, 1, 0), type);
        countdown(duel, COUNTDOWN_SECONDS);
    }

    private void enter(final Player player, final Location at, final DuelType type) {
        SavedState.clear(player);
        player.teleport(at);
        player.setGameMode(GameMode.ADVENTURE);
        giveLoadout(player, type);
    }

    private void giveLoadout(final Player player, final DuelType type) {
        final List<SmpSpec.WheelPrizeSpec> loadout = type == DuelType.SWORD
                ? config.duelLoadoutSword() : config.duelLoadoutBow();

        for (final SmpSpec.WheelPrizeSpec entry : loadout) {
            final Material material = Material.matchMaterial(
                    entry.item().trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("a duel loadout names '" + entry.item()
                        + "', which is not a material - that piece is missing from the fight");
                continue;
            }
            final ItemStack stack = new ItemStack(material, Math.max(1, entry.amount()));
            // Armour goes on rather than into the hotbar, which is what an "identical loadout"
            // has to mean if neither fighter is to spend the countdown dressing.
            if (!equipIfArmour(player, material, stack)) {
                player.getInventory().addItem(stack);
            }
        }
    }

    private static boolean equipIfArmour(final Player player, final Material material,
                                         final ItemStack stack) {
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

    private void countdown(final ActiveDuel duel, final int remaining) {
        if (!byPlayer.containsKey(duel.first())) {
            return;
        }
        forBoth(duel, player -> {
            player.setGameMode(remaining > 0 ? GameMode.ADVENTURE : GameMode.SURVIVAL);
            player.sendMessage(Component.text(remaining > 0
                    ? messages.format(locales.of(player.getUniqueId()), "smp.duel.countdown",
                            "seconds", remaining)
                    : messages.get(locales.of(player.getUniqueId()), "smp.duel.go")));
        });
        if (remaining > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> countdown(duel, remaining - 1), 20L);
        }
    }

    // ------------------------------------------------------------------ ending

    /** A fighter was defeated - by damage, or by disconnecting, which counts the same. */
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

        restore(duel, winnerId, "smp.duel.won");
        restore(duel, loserId, "smp.duel.lost");
        book(winnerId, loserId, duel);
        drainQueue();
    }

    private void restore(final ActiveDuel duel, final UUID playerId, final String messageKey) {
        final SavedState state = duel.saved().get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || state == null) {
            return;
        }
        state.restore(player);
        player.sendMessage(Component.text(messages.format(locales.of(playerId), messageKey,
                "aura", config.duelStake())));
    }

    /**
     * Books the stake and records the duel.
     *
     * <p>The stake is symmetrical: the winner gains it, the loser loses it, and the pair of aura
     * events is what makes the pair explicable afterwards. A death in the arena costs nothing
     * <em>beyond</em> this - that exception lives in the grave listener, which is the only other
     * place that reacts to a death.
     */
    private void book(final UUID winnerId, final UUID loserId, final ActiveDuel duel) {
        final Optional<String> winner = identities.discordIdOf(winnerId);
        final Optional<String> loser = identities.discordIdOf(loserId);
        if (winner.isEmpty() || loser.isEmpty()) {
            return;
        }
        final int stake = config.duelStake();
        final String type = duel.type().name();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dao.addAura(winner.get(), stake, AuraReason.DUEL_WIN.stored(), type);
            dao.addAura(loser.get(), -stake, AuraReason.DUEL_LOSS.stored(), type);
            dao.auraOf(winner.get()).ifPresent(value -> identities.recordAura(winnerId, value));
            dao.auraOf(loser.get()).ifPresent(value -> identities.recordAura(loserId, value));
        });
    }

    private void drainQueue() {
        while (!queue.isEmpty() && !slots.isFull()) {
            final Queued pair = queue.remove(0);
            final Player first = Bukkit.getPlayer(pair.first());
            final Player second = Bukkit.getPlayer(pair.second());
            if (first != null && second != null) {
                // pair.type(), not a guess: a bow pair that queued behind a sword pair must not be
                // handed swords when its turn comes.
                begin(first, second, pair.type());
            }
        }
    }

    // ------------------------------------------------------------------ the box

    private void build(final int slot, final Location centre) {
        final int radius = config.duelArenaRadius();
        final List<Location> blocks = new ArrayList<>();
        final World world = centre.getWorld();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                blocks.add(place(world, centre, x, 0, z));
                blocks.add(place(world, centre, x, 5, z));
            }
        }
        for (int y = 1; y <= 4; y++) {
            for (int i = -radius; i <= radius; i++) {
                blocks.add(place(world, centre, i, y, -radius));
                blocks.add(place(world, centre, i, y, radius));
                blocks.add(place(world, centre, -radius, y, i));
                blocks.add(place(world, centre, radius, y, i));
            }
        }
        blocks.removeIf(java.util.Objects::isNull);
        placed.put(slot, blocks);
    }

    /**
     * Places one block, and only into air.
     *
     * <p>The arena sits far above anything anybody builds, but "far above" is a configured number
     * and a season is long. Refusing to overwrite an existing block means the worst case is an arena
     * with a hole in it rather than a hole in somebody's tower.
     */
    private Location place(final World world, final Location centre, final int dx, final int dy,
                           final int dz) {
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

    /** Ends every duel and removes every arena. Called at disable. */
    public void stop() {
        List.copyOf(byPlayer.values()).forEach(duel -> {
            byPlayer.remove(duel.first());
            byPlayer.remove(duel.second());
            restore(duel, duel.first(), "smp.duel.interrupted");
            restore(duel, duel.second(), "smp.duel.interrupted");
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

    private void tell(final Player player, final String key) {
        player.sendMessage(Component.text(messages.get(locales.of(player.getUniqueId()), key)));
    }
}
