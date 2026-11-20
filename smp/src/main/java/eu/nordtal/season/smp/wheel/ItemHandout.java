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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

    private final Plugin plugin;
    private final PendingItems pending;
    private final Identities identities;
    private final MessageRenderer renderer;
    private final Executor background;

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

    /** Stores the stack for the player's next join, including its count and item data. */
    public void store(final DiscordId discordId, final ItemStack item) {
        final String material = item.getType().getKey().asString();
        final int amount = item.getAmount();
        final String data = Base64.getEncoder().encodeToString(item.asOne().serializeAsBytes());
        final Runnable write = () -> pending.store(discordId, material, amount, data);
        if (Bukkit.isPrimaryThread()) {
            CompletableFuture.runAsync(write, background).join();
        } else {
            write.run();
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        identities.discordIdOf(player.getUniqueId()).ifPresent(discordId -> {
            final PaperScheduler scheduler = PaperScheduler.of(plugin);
            scheduler.execute(() -> {
                if (pending.waitsFor(discordId)) {
                    scheduler.onMain(() -> handOut(player, discordId));
                }
            });
        });
    }

    private void handOut(final Player player, final DiscordId discordId) {
        if (!player.isOnline()) {
            return;
        }
        final Locale locale = identities.languageOf(player.getUniqueId());
        // Wait for the claim so a quit cannot run between the delete and delivery.
        for (final PendingItems.Item item : CompletableFuture.supplyAsync(() -> pending.claim(discordId), background)
                .join()) {
            try {
                final byte[] data = Base64.getDecoder().decode(item.data());
                give(player, ItemStack.deserializeBytes(data).asQuantity(item.amount()), locale);
            } catch (final RuntimeException failure) {
                plugin.getLogger()
                        .log(
                                Level.WARNING,
                                "item handout failed: discord_id=" + discordId.value() + " item=" + item.item(),
                                failure);
            }
        }
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
