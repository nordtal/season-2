package eu.nordtal.season.smp;

import eu.nordtal.season.papercommon.game.GameKeys;
import java.util.Base64;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Reads the item a setting names: the whole stack an admin took in from the game, or else a plain material. */
public final class TakenItems {

    private TakenItems() {}

    /**
     * Returns one of the item that {@code data} or {@code item} names, or null when neither reads.
     * {@code data} is Base64 of the stack's serialised bytes and wins; {@code item} names a plain material.
     */
    public static @Nullable ItemStack one(final @Nullable String item, final String data) {
        if (data.isBlank()) {
            if (item == null || item.isBlank()) {
                return null;
            }
            final Material material = GameKeys.material(item).orElse(null);
            return material == null ? null : new ItemStack(material);
        }
        try {
            final ItemStack stack =
                    ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
            return stack.isEmpty() ? null : stack.asOne();
        } catch (final RuntimeException unreadable) {
            return null;
        }
    }
}
