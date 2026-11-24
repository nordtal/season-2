package eu.nordtal.season.smp.item;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.inventory.ItemStack;

/** Central registry of Nordtal custom items handed out through the admin items menu. */
public final class CustomItems {

    /** One registered custom item with its stable id and stack factory. */
    public record Entry(String id, Supplier<ItemStack> factory) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(factory, "factory");
        }

        /** Returns a fresh single-item stack. */
        public ItemStack create() {
            return factory.get().clone();
        }
    }

    private final List<Entry> entries;

    public CustomItems(final List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Returns all registered custom items in menu order. */
    public List<Entry> all() {
        return entries;
    }
}
