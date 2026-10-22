package eu.nordtal.season.papercommon.player;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.mode.ModeStateStore;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Held;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.mode.ModeStateStore.Stack;
import eu.nordtal.season.database.mode.ModeStateStore.State;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps each player's Survival and Creative states apart on this server: inventory, ender chest and experience.
 * A switch waits for the database and stays cancelled if it fails. A leaving player's state is written and taken
 * back on joining, so Steward changes the states of a player who is away and asks this server about one who is here.
 */
public final class ModeStates implements Listener {

    private static final long WAIT_SECONDS = 5;

    private static final int INVENTORY_SLOTS = 41;
    private static final int ENDER_CHEST_SLOTS = 27;

    private final Plugin plugin;
    private final ModeStateStore store;
    private final Predicate<UUID> isAdmin;
    private final Logger logger;
    // The mode whose state the player holds, kept in the server's player data, since Spectator says nothing.
    private final NamespacedKey held;
    // Players whose switch is waiting for the database. Main thread only.
    private final Set<UUID> pending = new HashSet<>();
    // Every write of a state, one at a time, so a quit is written before the arrival that follows it.
    private final ExecutorService lane = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("mode-states").daemon().factory());
    // The state a player takes back, read before the join and applied at it.
    private final Map<UUID, Held> arriving = new ConcurrentHashMap<>();

    public ModeStates(
            final Plugin plugin, final ModeStateStore store, final Predicate<UUID> isAdmin, final Logger logger) {
        this.plugin = plugin;
        this.store = store;
        this.isAdmin = isAdmin;
        this.logger = logger;
        this.held = new NamespacedKey(plugin, "mode-state");
    }

    /** Returns the mode whose state {@code gameMode} uses, or null for Spectator, which uses none. */
    static @Nullable Mode of(final GameMode gameMode) {
        return switch (gameMode) {
            case SURVIVAL, ADVENTURE -> Mode.SURVIVAL;
            case CREATIVE -> Mode.CREATIVE;
            case SPECTATOR -> null;
        };
    }

    /**
     * Returns the mode whose state a player holds: the one recorded, else the one their game mode uses, else Survival.
     */
    static Mode held(final @Nullable String recorded, final GameMode gameMode) {
        if (recorded != null) {
            return Mode.valueOf(recorded);
        }
        final Mode mode = of(gameMode);
        return mode == null ? Mode.SURVIVAL : mode;
    }

    /** Returns whether a joining player must leave Creative's state: everybody but an admin. */
    static boolean leavesCreative(final boolean admin, final Mode held) {
        return !admin && held == Mode.CREATIVE;
    }

    private Mode held(final Player player) {
        return held(player.getPersistentDataContainer().get(held, PersistentDataType.STRING), player.getGameMode());
    }

    // MONITOR, so the state is taken only for a login no handler refused.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        final UUID id = event.getUniqueId();
        try {
            lane.submit(() -> store.arrive(plugin.getName(), PlayerId.of(id)))
                    .get(WAIT_SECONDS, TimeUnit.SECONDS)
                    .ifPresent(held -> arriving.put(id, held));
        } catch (final ExecutionException | TimeoutException failed) {
            logger.warn("{} joins with the state in the player data: the database did not answer", id, failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final Held back = arriving.remove(player.getUniqueId());
        if (back != null) {
            apply(player, back.state());
            player.getPersistentDataContainer()
                    .set(held, PersistentDataType.STRING, back.mode().name());
        }
        if (leavesCreative(isAdmin.test(player.getUniqueId()), held(player))) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    // HIGHEST, so a switch another handler cancels never reaches the database.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onModeChange(final PlayerGameModeChangeEvent event) {
        final Player player = event.getPlayer();
        final Mode entered = of(event.getNewGameMode());
        final Mode left = held(player);
        if (entered == null || entered == left) {
            return;
        }
        event.setCancelled(true);
        final UUID id = player.getUniqueId();
        if (!pending.add(id)) {
            return;
        }
        final GameMode wanted = event.getNewGameMode();
        final Snapshot leaving = Snapshot.of(player);
        final State stored = leaving.state();
        final PaperScheduler scheduler = PaperScheduler.of(plugin);
        lane.execute(() -> {
            final Optional<State> back;
            try {
                back = store.swap(plugin.getName(), PlayerId.of(id), left, stored, entered);
            } catch (final RuntimeException failed) {
                logger.warn("{} stays in {}: the database did not take the state", player.getName(), left, failed);
                scheduler.onMain(() -> refused(id));
                return;
            }
            scheduler.onMain(() -> {
                final Player now = plugin.getServer().getPlayer(id);
                // Anything that changed since the snapshot would be lost under the other state.
                if (now == null || !leaving.equals(Snapshot.of(now))) {
                    logger.info("{} changed while the switch to {} waited; it is not made", player.getName(), entered);
                    refused(id);
                    return;
                }
                pending.remove(id);
                apply(now, back.orElse(new State(0, 0f, List.of())));
                now.getPersistentDataContainer().set(held, PersistentDataType.STRING, entered.name());
                now.setGameMode(wanted);
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        leave(event.getPlayer());
    }

    /** Writes the state of every player still here and waits for every write. Paper disconnects them later. */
    public void close() {
        plugin.getServer().getOnlinePlayers().forEach(this::leave);
        lane.shutdown();
        try {
            if (!lane.awaitTermination(WAIT_SECONDS * 2, TimeUnit.SECONDS)) {
                logger.warn("the states of the players were not all written before the server stopped");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void leave(final Player player) {
        final Mode mode = held(player);
        final State state = Snapshot.of(player).state();
        final PlayerId id = PlayerId.of(player.getUniqueId());
        lane.execute(() -> {
            try {
                store.leave(plugin.getName(), id, mode, state);
            } catch (final RuntimeException failed) {
                logger.warn("the state {} left with stays in the player data alone", player.getName(), failed);
            }
        });
    }

    /** Stores a copy of the state a player here holds, so Steward shows it as it is. */
    public Answer storeHeld(final PlayerId player) {
        final Held now = onMain(() -> {
            final Player here = plugin.getServer().getPlayer(player.value());
            return here == null || pending.contains(here.getUniqueId())
                    ? null
                    : new Held(held(here), Snapshot.of(here).state());
        });
        if (now != null) {
            copy(player, now);
        }
        return Answer.done(Sent.of(Replies.STATE_STORED));
    }

    /** Removes the stack Steward showed in a slot of the state a player here holds. */
    public Answer remove(
            final PlayerId player,
            final Mode mode,
            final Container container,
            final int slot,
            final String item,
            final int amount) {
        final Held changed = onMain(() -> {
            final Player here = plugin.getServer().getPlayer(player.value());
            if (here == null || pending.contains(here.getUniqueId()) || held(here) != mode) {
                return null;
            }
            final Inventory inventory = container == Container.INVENTORY ? here.getInventory() : here.getEnderChest();
            final ItemStack stack = slot < inventory.getSize() ? inventory.getItem(slot) : null;
            if (stack == null
                    || stack.getAmount() != amount
                    || !stack.getType().getKey().asString().equals(item)) {
                return null;
            }
            inventory.setItem(slot, null);
            return new Held(mode, Snapshot.of(here).state());
        });
        if (changed == null) {
            return Answer.refused(ServerRefusal.STACK_CHANGED.with());
        }
        copy(player, changed);
        return Answer.done(Sent.of(Replies.STACK_REMOVED));
    }

    private void copy(final PlayerId player, final Held now) {
        waitFor(lane.submit(() -> store.copy(plugin.getName(), player, now.mode(), now.state())));
    }

    private <T> @Nullable T onMain(final Callable<@Nullable T> work) {
        return waitFor(plugin.getServer().getScheduler().callSyncMethod(plugin, work));
    }

    private static <T> T waitFor(final Future<T> work) {
        try {
            return work.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException failed) {
            throw new IllegalStateException(failed);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Ends a switch not made. A player who may not hold Creative's state watches as a spectator until rejoining. */
    private void refused(final UUID id) {
        pending.remove(id);
        final Player player = plugin.getServer().getPlayer(id);
        if (player != null
                && player.getGameMode() == GameMode.CREATIVE
                && leavesCreative(isAdmin.test(id), held(player))) {
            player.setGameMode(GameMode.SPECTATOR);
        }
    }

    private static void apply(final Player player, final State state) {
        final ItemStack[] inventory = new ItemStack[INVENTORY_SLOTS];
        final ItemStack[] enderChest = new ItemStack[ENDER_CHEST_SLOTS];
        for (final Stack stack : state.stacks()) {
            final ItemStack[] into = stack.container() == Container.INVENTORY ? inventory : enderChest;
            into[stack.slot()] = ItemStack.deserializeBytes(stack.data());
        }
        player.getInventory().setContents(inventory);
        player.getEnderChest().setContents(enderChest);
        player.setLevel(state.level());
        player.setExp(state.experience());
    }

    /** What a switch stores, copied so that a later change to the player shows as a difference. */
    private record Snapshot(
            List<@Nullable ItemStack> inventory, List<@Nullable ItemStack> enderChest, int level, float experience) {

        static Snapshot of(final Player player) {
            return new Snapshot(
                    copy(player.getInventory().getContents()),
                    copy(player.getEnderChest().getContents()),
                    player.getLevel(),
                    player.getExp());
        }

        private static List<@Nullable ItemStack> copy(final @Nullable ItemStack[] contents) {
            return Arrays.stream(contents)
                    .map(stack -> stack == null || stack.isEmpty() ? null : stack.clone())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        }

        State state() {
            final List<Stack> stacks = new ArrayList<>();
            add(stacks, Container.INVENTORY, inventory);
            add(stacks, Container.ENDER_CHEST, enderChest);
            return new State(level, experience, stacks);
        }

        private static void add(
                final List<Stack> stacks, final Container container, final List<@Nullable ItemStack> contents) {
            for (int slot = 0; slot < contents.size(); slot++) {
                final ItemStack stack = contents.get(slot);
                if (stack != null) {
                    stacks.add(new Stack(
                            container,
                            slot,
                            stack.getType().getKey().asString(),
                            stack.getAmount(),
                            stack.serializeAsBytes()));
                }
            }
        }
    }
}
