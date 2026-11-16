package eu.nordtal.season.papercommon.npc;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/** Keeps every figure of {@link Figures} from damage, fire and knockback. */
public final class FigureGuard implements Listener {

    private final Figures figures;

    public FigureGuard(final Figures figures) {
        this.figures = Objects.requireNonNull(figures, "figures");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageEvent event) {
        if (figures.is(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(final EntityCombustEvent event) {
        if (figures.is(event.getEntity())) {
            event.setCancelled(true);
            // Cancelling the event does not put out a fire that burns already.
            event.getEntity().setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onKnockback(final EntityKnockbackEvent event) {
        if (figures.is(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
