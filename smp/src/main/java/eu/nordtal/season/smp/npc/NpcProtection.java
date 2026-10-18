package eu.nordtal.season.smp.npc;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/** Cancels damage, combustion and knockback events of the spawn NPC, which {@link SpawnNpc#is} identifies. */
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
            // Cancelling the event does not extinguish a fire that already burns.
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
