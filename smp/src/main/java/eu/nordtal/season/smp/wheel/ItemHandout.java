package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messagerendering.GameLines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
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
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

public final class ItemHandout implements Listener {

    record Won(
            @Example("16") int amount,
            @Example("item.minecraft.diamond") GameContent item) {}

    static final DocumentSpec<Won> WON = DocumentSpec.game(
                    "smp", "wheel-won", "Wheel", DocumentSpec.Shape.CHAT, Won.class)
            .titled("Prize won");

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
    private final Set<Claim> claims = ConcurrentHashMap.newKeySet();
    /** One party for {@link #stop} and one for each write or claim still running. */
    private final Phaser running = new Phaser(1);

    public ItemHandout(
            final Plugin plugin,
            final PendingItems pending,
            final Identities identities,
            final MessageRenderer renderer,
            final Executor background) {
        this.plugin = plugin;
        this.pending = pending;
        this.identities = identities;
        this.renderer = renderer;
        this.background = background;
    }

    /**
     * Stores the stack, with its count and item data, for the player's next join on smp.
     * A player who is on smp by the time it is stored gets it at once. Callable from any thread; the write never runs
     * on the main thread.
     */
    public void store(final DiscordId discordId, final ItemStack item) {
        final String data = Base64.getEncoder().encodeToString(item.asOne().serializeAsBytes());
        put(discordId, List.of(new PendingItems.Item(item.getType().getKey().asString(), item.getAmount(), data)));
    }

    private void put(final DiscordId discordId, final List<PendingItems.Item> items) {
        final Runnable write =
                () -> items.forEach(each -> pending.store(discordId, each.item(), each.amount(), each.data()));
        if (!Bukkit.isPrimaryThread()) {
            write.run();
            handOutIfOnline(discordId);
        } else if (!plugin.isEnabled()) {
            // smp stops: the write lands before the item work closes, and the next join hands it out.
            CompletableFuture.runAsync(write, background).join();
        } else {
            inBackground(() -> {
                write.run();
                handOutIfOnline(discordId);
            });
        }
    }

    /** A player who joined while the write was in flight gets the items now instead of at the next join. */
    private void handOutIfOnline(final DiscordId discordId) {
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
        final Player player = Bukkit.getPlayer(claim.player);
        if (player == null || !player.isOnline()) {
            put(claim.discordId, claim.items);
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());
        for (final PendingItems.Item item : claim.items) {
            try {
                final byte[] data = Base64.getDecoder().decode(item.data());
                give(player, ItemStack.deserializeBytes(data).asQuantity(item.amount()), locale);
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .log(
                                Level.WARNING,
                                "item handout failed: discord_id=" + claim.discordId.value() + " item=" + item.item(),
                                failure);
            }
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
        final Location dropAt = Objects.requireNonNull(player.getLocation());
        player.getInventory()
                .addItem(item.clone())
                .values()
                .forEach(left -> player.getWorld().dropItemNaturally(dropAt, left));
        player.sendMessage(renderer.format(locale, WON.with(new Won(item.getAmount(), nameOf(item)))));
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
