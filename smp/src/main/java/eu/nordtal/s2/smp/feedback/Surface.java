package eu.nordtal.s2.smp.feedback;

import org.bukkit.inventory.InventoryHolder;

/**
 * A menu this plugin opens, marked so that {@link SurfaceListener} can hear it open and close.
 *
 * The grave inventory has a null holder and cannot carry it, so the listener takes a predicate for it.
 */
public interface Surface extends InventoryHolder {}
