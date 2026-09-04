package eu.nordtal.s2.smp.feedback;

import org.bukkit.inventory.InventoryHolder;

/**
 * A menu this plugin opens, marked so that {@link SurfaceListener} can hear it open and close.
 *
 * <p>The alternative was a {@code SURFACE_OPEN} call beside every {@code openInventory} and a
 * {@code SURFACE_CLOSE} beside every close - ten call sites for one rule, of which the next new menu
 * would quietly get nine. One word on the class instead, and the rule holds for anything that
 * carries it.
 *
 * <p>The grave inventory is the one surface that cannot implement this: it is created with a null
 * holder and looked up by identity in {@code Graves}, so the listener takes a predicate for it.
 */
public interface Surface extends InventoryHolder {
}
