package eu.nordtal.s2.smp.duel;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.smp.SmpMessages;
import eu.nordtal.s2.smp.aura.AuraReason;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.WheelPrizeSpec;
import eu.nordtal.s2.smp.db.SmpDao;
import eu.nordtal.s2.smp.feedback.SmpSounds;
import eu.nordtal.s2.smp.feedback.WorldEffects;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.world.WorldRole;
import eu.nordtal.s2.smp.world.Worlds;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * Duels: two 3 x 3 platforms at the spawn, an arena that appears above it, and one short fight.
 *
 * The duel's inventory, health and effects are its own, the loadouts are identical, and a disconnect is a defeat.
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
    private final SmpSounds sounds;
    private final WorldEffects effects;
    private final ArenaSlots slots;

    private final Map<DuelType, UUID> waiting = new HashMap<>();

    private final List<Queued> queue = new ArrayList<>();

    private final Map<UUID, ActiveDuel> byPlayer = new HashMap<>();

    /**
     * Fighters put back on their platform, who must step off before duelling again.
     *
     * The restore is a teleport, which counts as a move and would otherwise start the next duel at once.
     */
    private final java.util.Set<UUID> settled = new java.util.HashSet<>();

    /**
     * Fighters who died in the arena, and the state waiting for them until {@link #respawned}.
     *
     * The respawn overwrites an inventory written onto a dead player with the arena's loadout.
     */
    private final Map<UUID, SavedState> pending = new HashMap<>();

    private static final Title.Times OUTCOME = Title.Times.times(
            java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(2), java.time.Duration.ofMillis(600));

    /** Where a duel ends, for both fighters: the spawn, because the platform would restart the duel. */
    private @Nullable Location spawn() {
        return worlds.world(WorldRole.NORDTAL)
                .map(world -> eu.nordtal.s2.smp.world.LandingSite.safeAt(world, world.getSpawnLocation()))
                .orElse(null);
    }

    private final Map<Integer, List<Location>> placed = new HashMap<>();

    private final Clock clock;

    public Duels(
            final Plugin plugin,
            final SmpDao dao,
            final SmpSpec config,
            final Worlds worlds,
            final Identities identities,
            final Messages messages,
            final PlayerLocales locales,
            final SmpSounds sounds,
            final WorldEffects effects,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.worlds = worlds;
        this.identities = identities;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
        this.effects = effects;
        this.slots = new ArenaSlots(config.concurrentDuelLimit(), config.duelArenaBaseY(), config.duelArenaSpacing());
    }

    /** A pair that stepped on while every arena was busy. */
    private record Queued(UUID first, UUID second, DuelType type) {}

    /**
     * One running duel.
     *
     * discordIds is captured at the start: JoinGate clears Identities on quit before a disconnect is settled.
     */
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

    /** Whether a player is fighting. */
    public boolean isInArena(final Player player) {
        return byPlayer.containsKey(player.getUniqueId());
    }

    public void steppedOn(final Player player, final DuelType type) {
        if (isInArena(player) || settled.contains(player.getUniqueId())) {
            return;
        }
        final UUID other = waiting.get(type);
        if (other != null && other.equals(player.getUniqueId())) {
            // Already waiting: called on every block change inside the platform, several times at walking pace.
            return;
        }
        if (other == null) {
            waiting.put(type, player.getUniqueId());
            // SELECT, as for a menu click: the server noticed the choice.
            tell(player, MESSAGES.smp().duel().waiting(), Feedback.SELECT);
            return;
        }
        final Player opponent = Bukkit.getPlayer(other);
        if (opponent == null) {
            waiting.put(type, player.getUniqueId());
            return;
        }
        waiting.remove(type);
        // One tick later: Bukkit applies event.getTo() after handlers return, undoing a teleport made in the event.
        final java.util.UUID firstId = opponent.getUniqueId();
        final java.util.UUID secondId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            final Player first = Bukkit.getPlayer(firstId);
            final Player second = Bukkit.getPlayer(secondId);
            if (first != null && second != null) {
                begin(first, second, type);
            }
        });
    }

    /**
     * Gives a dead fighter their own life back, on the other side of the respawn screen.
     *
     * The location is set on the event and the inventory one tick later, after the respawn has written its own.
     */
    public void respawned(final org.bukkit.event.player.PlayerRespawnEvent event) {
        final SavedState state = pending.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        final Location where = spawn();
        if (where != null) {
            event.setRespawnLocation(where);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
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
            // Silent on purpose: whoever stepped on second heard SELECT in this same tick.
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

        // Read now, while both fighters are online.
        final Map<UUID, String> discordIds = new HashMap<>();
        identities.discordIdOf(first.getUniqueId()).ifPresent(id -> discordIds.put(first.getUniqueId(), id.value()));
        identities.discordIdOf(second.getUniqueId()).ifPresent(id -> discordIds.put(second.getUniqueId(), id.value()));

        final ActiveDuel duel = new ActiveDuel(
                first.getUniqueId(), second.getUniqueId(), type, slot.get(), saved, discordIds, clock.millis());
        byPlayer.put(first.getUniqueId(), duel);
        byPlayer.put(second.getUniqueId(), duel);

        final int radius = config.duelArenaRadius();
        // If the first fighter did not arrive, the second must not enter an arena that is about to be torn down.
        if (!enter(first, centre.clone().add(-radius + 1.5, 1, 0), type)
                || !enter(second, centre.clone().add(radius - 1.5, 1, 0), type)) {
            abort(duel);
            return;
        }
        countdown(duel, COUNTDOWN_SECONDS);
    }

    /** Unwinds a duel that never started: both fighters go back as they were, and nothing is booked. */
    private void abort(final ActiveDuel duel) {
        byPlayer.remove(duel.first());
        byPlayer.remove(duel.second());
        restore(duel, duel.first(), null);
        restore(duel, duel.second(), null);
        teardown(duel.slot());
        slots.release(duel.slot());
    }

    /**
     * Puts a fighter into the arena with the loadout.
     *
     * @return whether the player is standing in the arena; the caller aborts on false
     */
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
            final Material material = Material.matchMaterial(entry.item().trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger()
                        .warning("a duel loadout names '" + entry.item()
                                + "', which is not a material - that piece is missing from the fight");
                continue;
            }
            final ItemStack stack = new ItemStack(material, Math.max(1, entry.amount()));
            // Armour goes on rather than into the hotbar, so neither fighter spends the countdown dressing.
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

    private void countdown(final ActiveDuel duel, final int remaining) {
        if (!byPlayer.containsKey(duel.first())) {
            return;
        }
        forBoth(duel, player -> {
            player.setGameMode(remaining > 0 ? GameMode.ADVENTURE : GameMode.SURVIVAL);
            player.sendMessage(
                    remaining > 0
                            ? MessageRenderer.of(messages)
                                    .format(
                                            locales.of(player.getUniqueId()),
                                            MESSAGES.smp().duel().countdown(remaining))
                            : MessageRenderer.of(messages)
                                    .format(
                                            locales.of(player.getUniqueId()),
                                            MESSAGES.smp().duel().go()));
            // Four evenly spaced ticks, 3-2-1-Go: the last lands on the moment the fight starts.
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        });
        if (remaining > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> countdown(duel, remaining - 1), 20L);
        }
    }

    /** A fighter was defeated, by damage or by disconnecting. */
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

    /**
     * Gives a fighter their own state back.
     *
     * @param feedback the winner's or loser's sound, or {@code null} for a duel called off by stop()
     */
    private void restore(final ActiveDuel duel, final UUID playerId, final @Nullable Feedback feedback) {
        final SavedState state = duel.saved().get(playerId);
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || state == null) {
            return;
        }
        // Before the restore: the restore is the teleport that would otherwise start the next duel in the same tick.
        settled.add(playerId);
        if (player.isDead()) {
            // Nothing may be written onto a dead player, but the message and sound go out now.
            pending.put(playerId, state);
            // Triggered now: the pending map is process memory only, lost if the server stops mid-respawn.
            player.spigot().respawn();
        } else {
            state.restore(player, spawn());
        }
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final java.util.Locale locale = locales.of(playerId);
        final SmpMessages.Smp.Duel lines = MESSAGES.smp().duel();
        final int stake = config.duelStake();
        player.sendMessage(renderer.format(
                locale,
                feedback == null
                        ? lines.interrupted()
                        : feedback == Feedback.BIG_SUCCESS ? lines.won(stake) : lines.lost(stake)));
        // A title as well as the chat line: the line scrolls back, the title is what the hit player reads now.
        if (feedback != null) {
            final boolean won = feedback == Feedback.BIG_SUCCESS;
            player.showTitle(Title.title(
                    renderer.format(
                            locale,
                            won
                                    ? lines.wonSection().title()
                                    : lines.lostSection().title()),
                    renderer.format(
                            locale,
                            won
                                    ? lines.wonSection().subtitle(stake)
                                    : lines.lostSection().subtitle(stake)),
                    OUTCOME));
        }
        if (feedback != null) {
            sounds.play(player, feedback);
        }
    }

    /**
     * Books the stake and records the duel.
     *
     * The stake is symmetrical: the winner gains it, the loser loses it.
     */
    private void book(final UUID winnerId, final UUID loserId, final ActiveDuel duel) {
        // From the duel, not Identities: on a disconnect the cache is already cleared by the time this runs.
        final String winner = duel.discordIds().get(winnerId);
        final String loser = duel.discordIds().get(loserId);
        if (winner == null || loser == null) {
            return;
        }
        final int stake = config.duelStake();
        final String type = duel.type().name();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            dao.addAura(DiscordId.of(winner), stake, AuraReason.DUEL_WIN.stored(), type);
            dao.addAura(DiscordId.of(loser), -stake, AuraReason.DUEL_LOSS.stored(), type);
            dao.auraOf(DiscordId.of(winner)).ifPresent(value -> identities.recordAura(winnerId, value));
            dao.auraOf(DiscordId.of(loser)).ifPresent(value -> identities.recordAura(loserId, value));
        });
    }

    private void drainQueue() {
        while (!queue.isEmpty() && !slots.isFull()) {
            final Queued pair = queue.remove(0);
            final Player first = Bukkit.getPlayer(pair.first());
            final Player second = Bukkit.getPlayer(pair.second());
            if (first != null && second != null) {
                // pair.type(), not a guess: a bow pair queued behind a sword pair must not be handed swords.
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

    /** Places one block, and only into air, so the worst case is a hole in the arena and never in a build. */
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

    /** Ends every duel and removes every arena, at disable. */
    public void stop() {
        List.copyOf(byPlayer.values()).forEach(duel -> {
            byPlayer.remove(duel.first());
            byPlayer.remove(duel.second());
            // No sound: a duel called off because the server is stopping cost nobody anything.
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

    /**
     * The same, plus a sound.
     *
     * A {@code null} feedback is ordinary: only moments that change what the player can do get one.
     */
    private void tell(final Player player, final MessageRef message, final @Nullable Feedback feedback) {
        player.sendMessage(MessageRenderer.of(messages).format(locales.of(player.getUniqueId()), message));
        if (feedback != null) {
            sounds.play(player, feedback);
        }
    }
}
