package eu.nordtal.season.papercommon.player;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.time.SerialLane;
import eu.nordtal.season.database.mode.ModeStateStore;
import eu.nordtal.season.database.mode.ModeStateStore.Container;
import eu.nordtal.season.database.mode.ModeStateStore.Held;
import eu.nordtal.season.database.mode.ModeStateStore.Mode;
import eu.nordtal.season.database.mode.ModeStateStore.Stack;
import eu.nordtal.season.database.mode.ModeStateStore.State;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
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
    private final PaperScheduler scheduler;
    // Every write of a state, one at a time, so a quit is written before the arrival that follows it.
    private final Executor lane;
    // The state a player takes back, read before the join and applied at it.
    private final Map<UUID, Held> arriving = new ConcurrentHashMap<>();
    // The editors open on this server, by the player whose state they show. Changed on the main thread only.
    private final Map<UUID, List<InventoryEditor>> editors = new ConcurrentHashMap<>();

    public ModeStates(
            final Plugin plugin, final ModeStateStore store, final Predicate<UUID> isAdmin, final Logger logger) {
        this.plugin = plugin;
        this.store = store;
        this.isAdmin = isAdmin;
        this.logger = logger;
        this.held = new NamespacedKey(plugin, "mode-state");
        this.scheduler = PaperScheduler.of(plugin);
        // Paper takes no work from a disabled plugin, so what is queued at close runs on the closing thread.
        this.lane = new SerialLane(work -> {
            if (plugin.isEnabled()) {
                scheduler.execute(work);
            } else {
                work.run();
            }
        });
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
        if (editors.containsKey(id)) {
            // An editor's write of the stored state goes ahead of the arrival that holds it.
            try {
                waitFor(CompletableFuture.runAsync(() -> closeEditors(id), scheduler.mainThread()));
            } catch (final IllegalStateException failed) {
                logger.warn("an editor of {} stays open as they join; the arrival refuses its write", id, failed);
            }
        }
        try {
            CompletableFuture.supplyAsync(() -> store.arrive(plugin.getName(), PlayerId.of(id)), lane)
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
        // An editor of the stored state left open by a slow prelogin writes nothing over the state the player holds.
        closeEditors(player.getUniqueId());
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
        if (pending.contains(id)) {
            return;
        }
        // An admin's own editor puts their cursor back, and every editor of the player closes, before the snapshot.
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof InventoryEditor) {
            player.closeInventory();
        }
        closeEditors(id);
        pending.add(id);
        final GameMode wanted = event.getNewGameMode();
        final Snapshot leaving = Snapshot.of(player);
        final State stored = leaving.state();
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
        final Player player = event.getPlayer();
        // An admin's own editor gives its stacks back before their state is written.
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof InventoryEditor) {
            player.closeInventory();
        }
        closeEditors(player.getUniqueId());
        leave(player);
    }

    /** Writes the state of every player still here and waits for every write. Paper disconnects them later. */
    public void close() {
        // An admin's cursor goes back into their inventory before their state is written.
        plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> player.getOpenInventory().getTopInventory().getHolder() instanceof InventoryEditor)
                .forEach(Player::closeInventory);
        List.copyOf(editors.values()).forEach(open -> List.copyOf(open).forEach(InventoryEditor::commit));
        plugin.getServer().getOnlinePlayers().forEach(this::leave);
        try {
            CompletableFuture.runAsync(() -> {}, lane).get(WAIT_SECONDS * 2, TimeUnit.SECONDS);
        } catch (final ExecutionException | TimeoutException failed) {
            logger.warn("the states of the players were not all written before the server stopped", failed);
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
    public void storeHeld(final PlayerId player) {
        final Held now = onMain(() -> {
            final Player here = plugin.getServer().getPlayer(player.value());
            return here == null || pending.contains(here.getUniqueId())
                    ? null
                    : new Held(held(here), Snapshot.of(here).state());
        });
        if (now != null) {
            copy(player, now);
        }
    }

    /** Removes the stack Steward showed in a slot of the state a player here holds; false if the slot changed. */
    public boolean remove(
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
            return false;
        }
        copy(player, changed);
        return true;
    }

    /** What became of the open of an editor. */
    public enum Opened {
        OPENED,
        /** The admin is not on this server. */
        ADMIN_AWAY,
        /** This server has no Survival state of the player to edit now, or an editor of the stored one is open. */
        NOT_EDITABLE
    }

    /**
     * Opens an editor of a player's Survival {@code container} for an admin here. Called off the main thread.
     * It shows the live inventory of a player who holds the state here, else the stored state.
     *
     * @param kept tells the admin how many slots changed meanwhile, so that nothing was written
     */
    public Opened open(
            final PlayerId admin,
            final PlayerId player,
            final Container container,
            final Component title,
            final IntConsumer kept) {
        if (!Boolean.TRUE.equals(onMain(() -> plugin.getServer().getPlayer(admin.value()) != null))) {
            return Opened.ADMIN_AWAY;
        }
        final Boolean live = onMain(() -> survivalHere(player.value()));
        if (live == null) {
            return Opened.NOT_EDITABLE;
        }
        final List<@Nullable Stack> stored =
                live ? null : waitFor(CompletableFuture.supplyAsync(() -> stored(player, container), lane));
        if (!live && stored == null) {
            return Opened.NOT_EDITABLE;
        }
        final Opened opened = onMain(() -> {
            final Player viewer = plugin.getServer().getPlayer(admin.value());
            final Player here = plugin.getServer().getPlayer(player.value());
            if (viewer == null) {
                return Opened.ADMIN_AWAY;
            }
            final Boolean now = survivalHere(player.value());
            if (now == null
                    || now.booleanValue() != live.booleanValue()
                    || (!live && storedEditorOpen(player, container))) {
                return Opened.NOT_EDITABLE;
            }
            final List<@Nullable ItemStack> shown = here != null && live
                    ? Snapshot.copy(contents(here, container).getContents())
                    : stacks(Objects.requireNonNull(stored, "stored"));
            final InventoryEditor editor =
                    new InventoryEditor(this, admin, player, container, title, shown, stored, kept);
            editor.open(viewer);
            if (viewer.getOpenInventory().getTopInventory().getHolder() != editor) {
                return Opened.NOT_EDITABLE;
            }
            editors.computeIfAbsent(player.value(), id -> new ArrayList<>()).add(editor);
            if (here != null && live) {
                editor.refreshing(scheduler.onMainEvery(
                        PaperScheduler.TICK,
                        PaperScheduler.TICK,
                        () -> editor.show(contents(here, container).getContents())));
            }
            return Opened.OPENED;
        });
        return opened == null ? Opened.NOT_EDITABLE : opened;
    }

    /**
     * Returns whether a player here holds Survival's state, or null while a switch or an arrival waits.
     * False means the stored state is the one to edit.
     */
    private @Nullable Boolean survivalHere(final UUID player) {
        final Player here = plugin.getServer().getPlayer(player);
        if (pending.contains(player) || arriving.containsKey(player)) {
            return null;
        }
        return here != null && held(here) == Mode.SURVIVAL;
    }

    /** Returns whether an editor of {@code player}'s stored {@code container} is open here; one at a time may be. */
    private boolean storedEditorOpen(final PlayerId player, final Container container) {
        return editors.getOrDefault(player.value(), List.of()).stream()
                .anyMatch(open -> !open.live() && open.container() == container);
    }

    /**
     * Makes an admin's click on a slot of a live editor in the player's inventory, on the next tick.
     * The cursor and the slot change together on the main thread, so no stack is made or lost.
     */
    void move(final InventoryEditor editor, final Player admin, final int slot, final boolean right) {
        scheduler.onMain(() -> {
            final Player here = plugin.getServer().getPlayer(editor.player().value());
            if (here == null
                    || !Boolean.TRUE.equals(survivalHere(here.getUniqueId()))
                    || admin.getOpenInventory().getTopInventory().getHolder() != editor) {
                return;
            }
            final Inventory inventory = contents(here, editor.container());
            final ItemStack cursor = InventoryEditor.present(admin.getItemOnCursor());
            final ItemStack held = InventoryEditor.present(inventory.getItem(slot));
            final ItemStack kind = cursor != null ? cursor : held;
            if (kind == null) {
                return;
            }
            final boolean similar = cursor != null && held != null && cursor.isSimilar(held);
            final InventoryEditor.Move move = InventoryEditor.move(
                    right,
                    cursor == null ? 0 : cursor.getAmount(),
                    held == null ? 0 : held.getAmount(),
                    similar,
                    Math.min(kind.getMaxStackSize(), inventory.getMaxStackSize()));
            if (move.swap()) {
                inventory.setItem(slot, cursor);
                admin.setItemOnCursor(held);
            } else {
                final ItemStack slotKind = held != null ? held : kind;
                final ItemStack cursorKind = cursor != null ? cursor : kind;
                inventory.setItem(slot, move.slot() == 0 ? null : slotKind.asQuantity(move.slot()));
                admin.setItemOnCursor(move.cursor() == 0 ? null : cursorKind.asQuantity(move.cursor()));
            }
            editor.moved();
            editor.show(inventory.getContents());
        });
    }

    /** Returns the stored Survival stacks of {@code container} by slot, or null when this server holds the state. */
    private @Nullable List<@Nullable Stack> stored(final PlayerId player, final Container container) {
        final Optional<ModeStateStore.Stored> state = store.states(player).stream()
                .filter(row -> row.server().equals(plugin.getName()) && row.mode() == Mode.SURVIVAL)
                .findFirst();
        if (state.isEmpty() || state.get().status() == ModeStateStore.Status.HELD) {
            return null;
        }
        final List<@Nullable Stack> stacks = new ArrayList<>();
        final int size = container == Container.INVENTORY ? INVENTORY_SLOTS : ENDER_CHEST_SLOTS;
        for (int slot = 0; slot < size; slot++) {
            stacks.add(null);
        }
        state.get().state().stacks().stream()
                .filter(stack -> stack.container() == container && stack.slot() < size)
                .forEach(stack -> stacks.set(stack.slot(), stack));
        return stacks;
    }

    private static List<@Nullable ItemStack> stacks(final List<@Nullable Stack> stored) {
        return stored.stream()
                .map(stack -> stack == null ? null : ItemStack.deserializeBytes(stack.data()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private static Inventory contents(final Player player, final Container container) {
        return container == Container.INVENTORY ? player.getInventory() : player.getEnderChest();
    }

    /** Closes every editor of {@code player}'s state, so its changes are written before the state moves. */
    private void closeEditors(final UUID player) {
        final List<InventoryEditor> open = editors.get(player);
        if (open == null) {
            return;
        }
        for (final InventoryEditor editor : List.copyOf(open)) {
            for (final HumanEntity viewer : List.copyOf(editor.getInventory().getViewers())) {
                viewer.closeInventory();
            }
            editor.commit();
        }
    }

    /**
     * Writes the slots an editor changed, all of them if each still holds what the window showed, on the main thread.
     * A slot that changed meanwhile keeps its stack, and the admin gets theirs back.
     */
    void commit(final InventoryEditor editor) {
        final List<InventoryEditor> open = editors.get(editor.player().value());
        if (open != null && open.remove(editor) && open.isEmpty()) {
            editors.remove(editor.player().value());
        }
        if (editor.live()) {
            copyMoved(editor);
            return;
        }
        final List<InventoryEditor.Change> changes = editor.changes();
        if (changes.isEmpty()) {
            settle(editor, changes, Set.of());
        } else {
            final List<ModeStateStore.SlotChange> writes = changes.stream()
                    .map(change -> new ModeStateStore.SlotChange(
                            change.slot(),
                            editor.storedAt(change.slot()),
                            Snapshot.stack(editor.container(), change.slot(), change.after())))
                    .toList();
            lane.execute(() -> {
                Set<Integer> refused;
                try {
                    refused = store.edit(plugin.getName(), editor.player(), Mode.SURVIVAL, editor.container(), writes);
                } catch (final RuntimeException failed) {
                    logger.warn("the edit of {}'s stored state was not written", editor.player(), failed);
                    refused = Set.copyOf(
                            changes.stream().map(InventoryEditor.Change::slot).toList());
                }
                final Set<Integer> kept = refused;
                if (plugin.isEnabled()) {
                    scheduler.onMain(() -> settle(editor, changes, kept));
                } else {
                    settle(editor, changes, kept);
                }
            });
        }
    }

    /** Stores a copy of the live state an editor moved stacks in, so Steward shows it as it is. */
    private void copyMoved(final InventoryEditor editor) {
        final Player here = plugin.getServer().getPlayer(editor.player().value());
        if (!editor.wasMoved() || here == null || !Boolean.TRUE.equals(survivalHere(here.getUniqueId()))) {
            return;
        }
        final State state = Snapshot.of(here).state();
        lane.execute(() -> {
            try {
                store.copy(plugin.getName(), editor.player(), Mode.SURVIVAL, state);
            } catch (final RuntimeException failed) {
                logger.warn("the copy of {}'s edited state was not written", here.getName(), failed);
            }
        });
    }

    /**
     * Evens out a refused stored edit with the admin, and says how many slots changed meanwhile.
     * By kind, the admin gives back what they took from the copy and gets what they put in, and any strays.
     */
    private void settle(
            final InventoryEditor editor, final List<InventoryEditor.Change> changes, final Set<Integer> refused) {
        final List<ItemStack> back = new ArrayList<>(editor.strays());
        final List<ItemStack> taken = new ArrayList<>();
        if (!refused.isEmpty()) {
            InventoryEditor.net(
                            amounts(changes, InventoryEditor.Change::before),
                            amounts(changes, InventoryEditor.Change::after))
                    .forEach((kind, amount) -> (amount > 0 ? back : taken).add(kind.asQuantity(Math.abs(amount))));
        }
        final Player admin = plugin.getServer().getPlayer(editor.admin().value());
        if (admin == null) {
            if (!back.isEmpty() || !taken.isEmpty()) {
                logger.warn(
                        "{} left before an edit was evened out; {} stay lost, {} stay theirs",
                        editor.admin(),
                        back,
                        taken);
            }
            return;
        }
        final Collection<ItemStack> missing =
                admin.getInventory().removeItem(taken.toArray(ItemStack[]::new)).values();
        if (!missing.isEmpty()) {
            logger.warn("{} no longer holds {} they took from a copy whose edit was refused", admin.getName(), missing);
        }
        admin.getInventory()
                .addItem(back.toArray(ItemStack[]::new))
                .values()
                .forEach(left -> admin.getWorld().dropItemNaturally(admin.getLocation(), left));
        if (!refused.isEmpty()) {
            editor.kept(refused.size());
        }
    }

    /** Returns the stacks one side of the changes holds, each as one of its kind with its amount. */
    private static List<Map.Entry<ItemStack, Integer>> amounts(
            final List<InventoryEditor.Change> changes,
            final Function<InventoryEditor.Change, @Nullable ItemStack> side) {
        return changes.stream()
                .map(side)
                .filter(Objects::nonNull)
                .map(stack -> Map.entry(stack.asOne(), stack.getAmount()))
                .toList();
    }

    private void copy(final PlayerId player, final Held now) {
        waitFor(CompletableFuture.runAsync(() -> store.copy(plugin.getName(), player, now.mode(), now.state()), lane));
    }

    private <T> @Nullable T onMain(final Supplier<@Nullable T> work) {
        return waitFor(CompletableFuture.supplyAsync(work, scheduler.mainThread()));
    }

    private static <T> T waitFor(final CompletableFuture<T> work) {
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

        static List<@Nullable ItemStack> copy(final @Nullable ItemStack[] contents) {
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

        static @Nullable Stack stack(final Container container, final int slot, final @Nullable ItemStack stack) {
            return stack == null
                    ? null
                    : new Stack(
                            container,
                            slot,
                            stack.getType().getKey().asString(),
                            stack.getAmount(),
                            stack.serializeAsBytes());
        }

        private static void add(
                final List<Stack> stacks, final Container container, final List<@Nullable ItemStack> contents) {
            for (int slot = 0; slot < contents.size(); slot++) {
                final Stack stored = stack(container, slot, contents.get(slot));
                if (stored != null) {
                    stacks.add(stored);
                }
            }
        }
    }
}
