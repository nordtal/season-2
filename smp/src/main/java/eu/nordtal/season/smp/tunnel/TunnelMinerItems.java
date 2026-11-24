package eu.nordtal.season.smp.tunnel;

import eu.nordtal.season.smp.SmpBootstrap;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Looks up the Tunnel Miner enchantment and builds or detects Tunnel Miner items. */
public final class TunnelMinerItems {

    private TunnelMinerItems() {}

    /** Returns the registered {@code nordtal:tunnel_miner} enchantment, or null if not registered. */
    public static @Nullable Enchantment enchantment() {
        return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(SmpBootstrap.TUNNEL_MINER_KEY);
    }

    /** Creates an Enchanted Book storing Tunnel Miner I for the admin items menu. */
    public static ItemStack enchantedBook() {
        final Enchantment enchantment = enchantment();
        if (enchantment == null) {
            throw new IllegalStateException("nordtal:tunnel_miner enchantment is not registered");
        }
        return enchantedBook(enchantment);
    }

    /** Creates an Enchanted Book storing {@code enchantment} at level 1. */
    public static ItemStack enchantedBook(final Enchantment enchantment) {
        final ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.setData(
                DataComponentTypes.STORED_ENCHANTMENTS,
                ItemEnchantments.itemEnchantments().add(enchantment, 1).build());
        return book;
    }

    /** Returns whether {@code stack} carries the Tunnel Miner enchantment. */
    public static boolean hasTunnelMiner(final @Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final Enchantment enchantment = enchantment();
        return enchantment != null && stack.getEnchantmentLevel(enchantment) > 0;
    }
}
