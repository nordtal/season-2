package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.GameLines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.TakenItems;
import eu.nordtal.season.smp.milestone.Objective.ItemReward;
import eu.nordtal.season.smp.port.ItemRewards;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

public final class ItemHandout implements Listener, ItemRewards {

    record Won(
            @Example("16") int amount,
            @Example("item.minecraft.diamond") GameContent item) {}

    static final DocumentSpec<Won> WON = DocumentSpec.game(
                    "smp", "wheel-won", "Wheel", DocumentSpec.Shape.CHAT, Won.class)
            .titled("Prize won");

    static final DocumentSpec<Won> REWARD = DocumentSpec.game(
                    "smp", "objective-item-reward", "Objectives", DocumentSpec.Shape.CHAT, Won.class)
            .titled("Objective item received");

    static final DocumentSpec<Won> STAKE_RETURNED = DocumentSpec.game(
                    "smp", "duel-stake-returned", "Duels", DocumentSpec.Shape.CHAT, Won.class)
            .titled("Duel stake returned");

    /** What a claim does with a player. */
    enum Delivery {
        /** The player is free: the items go into the inventory now. */
        GIVE,
        /** The player left: the rows go back and wait for the next join. */
        STORE,
        /**
         * The player is dead, or a duel still writes their inventory: the rows go back.
         *
         * The respawn or the duel's restore claims them again.
         */
        WAIT;

        static Delivery of(final boolean online, final boolean dead, final boolean held) {
            if (!online) {
                return STORE;
            }
            return dead || held ? WAIT : GIVE;
        }
    }

    /** Rows taken from the store and not given yet. */
    private static final class Claim {
        private final DiscordId discordId;
        private final UUID player;
        private final List<PendingItems.Item> items;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Claim(final DiscordId discordId, final UUID player, final List<PendingItems.Item> items) {
            this.discordId = discordId;
            this.player = player;
            this.items = items;
        }

        /** True for the one caller that gives the items or puts them back. */
        private boolean settle() {
            return settled.compareAndSet(false, true);
        }
    }

    private static final long STOP_WAIT_SECONDS = 10;

    private final Plugin plugin;
    private final PendingItems pending;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final Executor background;
    private final Supplier<World> overworld;
    private final Predicate<UUID> held;
    private final Set<Claim> claims = ConcurrentHashMap.newKeySet();
    /** One party for {@link #stop} and one for each write or claim still running. */
    private final Phaser running = new Phaser(1);

    public ItemHandout(
            final Plugin plugin,
            final PendingItems pending,
            final Identities identities,
            final MessageRenderer renderer,
            final Executor background,
            final Supplier<World> overworld,
            final Predicate<UUID> held) {
        this.plugin = plugin;
        this.pending = pending;
        this.identities = identities;
        this.renderer = renderer;
        this.background = background;
        this.overworld = overworld;
        this.held = held;
    }

    /**
     * Stores the stack, with its count and item data, for the player's next join on smp.
     * A player who is on smp by the time it is stored gets it at once. Callable from any thread; the write never runs
     * on the main thread.
     */
    public void store(final DiscordId discordId, final ItemStack item) {
        final String data = Base64.getEncoder().encodeToString(item.asOne().serializeAsBytes());
        put(
                discordId,
                List.of(new PendingItems.Item(
                        item.getType().getKey().asString(), item.getAmount(), data, "", false, false)));
    }

    @Override
    public void queue(final DiscordId recipient, final ItemReward reward) {
        pending.storeReward(recipient, reward);
    }

    void storeMap(final DiscordId discordId, final String item, final int amount, final String structure) {
        put(discordId, List.of(new PendingItems.Item(item, amount, "", structure, false, false)));
    }

    private void put(final DiscordId discordId, final List<PendingItems.Item> items) {
        put(discordId, items, true);
    }

    private void put(final DiscordId discordId, final List<PendingItems.Item> items, final boolean notify) {
        final Runnable write = () -> {
            items.forEach(each -> pending.store(
                    discordId,
                    each.item(),
                    each.amount(),
                    each.data(),
                    each.structure(),
                    each.objectiveReward(),
                    each.returnedStake()));
            if (notify && plugin.isEnabled()) {
                handOutIfOnline(discordId);
            }
        };
        if (!Bukkit.isPrimaryThread()) {
            write.run();
        } else if (!plugin.isEnabled()) {
            // smp stops: the write lands before the item work closes, and the next join hands it out.
            CompletableFuture.runAsync(write, background).join();
        } else {
            inBackground(write);
        }
    }

    /** A player who joined while the write was in flight gets the items now instead of at the next join. */
    @Override
    public void handOutIfOnline(final DiscordId discordId) {
        try {
            PaperScheduler.of(plugin)
                    .onMain(() -> Bukkit.getOnlinePlayers().stream()
                            .filter(player -> identities
                                    .discordIdOf(player.getUniqueId())
                                    .filter(discordId::equals)
                                    .isPresent())
                            .findFirst()
                            .ifPresent(player -> claimFor(player.getUniqueId(), discordId)));
        } catch (final RejectedExecutionException stopping) {
            // The rows wait for the next join.
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final UUID player = event.getPlayer().getUniqueId();
        identities.discordIdOf(player).ifPresent(discordId -> claimFor(player, discordId));
    }

    /** A claim that met a dead player went back to the store; the respawn claims it again, unless a duel holds it. */
    @EventHandler
    public void onRespawn(final PlayerRespawnEvent event) {
        final UUID player = event.getPlayer().getUniqueId();
        identities.discordIdOf(player).ifPresent(discordId -> claimFor(player, discordId));
    }

    /** Claims the rows off the main thread and gives them on it; a claim smp cannot give goes back to the store. */
    private void claimFor(final UUID player, final DiscordId discordId) {
        inBackground(() -> {
            if (!pending.waitsFor(discordId)) {
                return;
            }
            final Claim claim = new Claim(discordId, player, pending.claim(discordId));
            if (claim.items.isEmpty()) {
                return;
            }
            claims.add(claim);
            try {
                PaperScheduler.of(plugin).onMain(() -> handOut(claim));
            } catch (final RejectedExecutionException stopping) {
                putBack(claim);
            }
        });
    }

    private void handOut(final Claim claim) {
        if (!claim.settle()) {
            return;
        }
        claims.remove(claim);
        final @Nullable Player player = Bukkit.getPlayer(claim.player);
        final Delivery delivery = player == null
                ? Delivery.STORE
                : Delivery.of(player.isOnline(), player.isDead(), held.test(claim.player));
        if (player == null || delivery != Delivery.GIVE) {
            put(claim.discordId, claim.items, delivery == Delivery.STORE);
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());
        for (final PendingItems.Item item : claim.items) {
            try {
                deliver(player, claim.discordId, item, locale);
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .log(
                                Level.WARNING,
                                "item handout failed: discord_id=" + claim.discordId.value() + " item=" + item.item(),
                                failure);
            }
        }
    }

    private void deliver(
            final Player player, final DiscordId recipient, final PendingItems.Item item, final Locale locale) {
        final ItemStack stack;
        if (item.structure().isBlank()) {
            final ItemStack one = TakenItems.one(item.item(), item.data());
            stack = Objects.requireNonNull(one).asQuantity(item.amount());
        } else {
            stack = makeMap(player, recipient, item);
            if (stack == null) {
                put(recipient, List.of(item), false);
                return;
            }
        }
        give(player, stack, locale, item.returnedStake() ? STAKE_RETURNED : item.objectiveReward() ? REWARD : WON);
    }

    private @Nullable ItemStack makeMap(final Player player, final DiscordId recipient, final PendingItems.Item item) {
        try {
            final ItemStack map = ExplorerMaps.create(
                    overworld.get(),
                    Objects.requireNonNull(player.getLocation()),
                    item.item(),
                    item.amount(),
                    item.structure());
            if (map == null) {
                plugin.getLogger()
                        .warning("explorer map deferred, no destination found: discord_id=" + recipient.value()
                                + " structure=" + item.structure());
            }
            return map;
        } catch (final RuntimeException failure) {
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "explorer map deferred: discord_id=" + recipient.value() + " structure=" + item.structure(),
                            failure);
            return null;
        }
    }

    private void putBack(final Claim claim) {
        if (claim.settle()) {
            claims.remove(claim);
            put(claim.discordId, claim.items);
        }
    }

    /** Runs {@code work} on the item work, where {@link #stop} waits for it. */
    private void inBackground(final Runnable work) {
        running.register();
        try {
            background.execute(() -> {
                try {
                    work.run();
                } finally {
                    running.arriveAndDeregister();
                }
            });
        } catch (final RuntimeException refused) {
            running.arriveAndDeregister();
            throw refused;
        }
    }

    /**
     * Waits for the writes and claims still running, then puts back every claim the main thread has not given yet.
     * smp calls it while it stops, before the item work closes, so a stop between claim and hand-out loses nothing.
     */
    public void stop() {
        try {
            running.awaitAdvanceInterruptibly(running.arrive(), STOP_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException late) {
            plugin.getLogger().warning("item tasks still running at stop: count=" + running.getUnarrivedParties());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        List.copyOf(claims).forEach(this::putBack);
    }

    void give(final Player player, final ItemStack item, final Locale locale) {
        give(player, item, locale, WON);
    }

    private void give(
            final Player player, final ItemStack item, final Locale locale, final DocumentSpec<Won> document) {
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(item.clone())
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
        player.sendMessage(renderer.format(locale, document.with(new Won(item.getAmount(), nameOf(item)))));
    }

    private static GameContent nameOf(final ItemStack item) {
        return named(
                item.getData(DataComponentTypes.CUSTOM_NAME),
                item.isDataOverridden(DataComponentTypes.ITEM_NAME) ? item.getData(DataComponentTypes.ITEM_NAME) : null,
                item.getType().translationKey());
    }

    static GameContent named(
            final @Nullable Component customName, final @Nullable Component itemName, final String materialKey) {
        final Component name = customName != null ? customName : itemName;
        return name == null ? GameContent.of(materialKey) : GameLines.of(name);
    }
}
