package eu.nordtal.s2.smp.npc;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Nothing may hurt, burn or shove the figure in the tavern, the only way to fulfil a hand-in.
 *
 * Creative players and the void pass {@code setInvulnerable}; each handler checks {@link SpawnNpc#is}.
 */
public final class NpcProtection implements Listener {

    private final SpawnNpc npc;

    public NpcProtection(final SpawnNpc npc) {
        this.npc = npc;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageEvent event) {
        if (npc.is(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(final EntityCombustEvent event) {
        if (npc.is(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
            // Cancelling stops combustion about to be applied; it does not undo one that already is.
            event.getEntity().setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKnockback(final EntityKnockbackEvent event) {
        if (npc.is(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
