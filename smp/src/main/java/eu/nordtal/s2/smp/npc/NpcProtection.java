package eu.nordtal.s2.smp.npc;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Nothing may hurt, burn or shove the figure in the tavern.
 *
 * <h2>Why {@code setInvulnerable(true)} is not the whole answer</h2>
 * {@link org.bukkit.entity.Entity#setInvulnerable(boolean)} is the vanilla invulnerability flag, and
 * vanilla itself carries two documented ways past it: a player in <b>creative mode</b> hits through
 * it - which is what every admin standing next to the NPC is in - and the <b>void</b> ignores it
 * outright. The spawn protection covers blocks and not entities, so it protects nothing here either.
 * The figure is the only way a {@code HAND_IN} objective can be fulfilled, so losing it is losing
 * the milestone track until somebody restarts the server (finding 150).
 *
 * <h2>By identity, never by type</h2>
 * Every handler asks {@link SpawnNpc#is(java.util.UUID)}. A check on {@code instanceof Mannequin}
 * would protect every mannequin on the server - a decoration somebody built, a mannequin in a
 * player's own base - and turn an ordinary entity into one nobody can ever remove. The figure this
 * plugin placed is the only one it has any business defending.
 *
 * <h2>Three events, and each is a different way to lose it</h2>
 * <ul>
 *   <li>{@link EntityDamageEvent} is the hit, the void, the lava and the drowning. Cancelling it
 *       also removes the knockback that a hit would have applied, because that knockback is a
 *       consequence of damage that was dealt.</li>
 *   <li>{@link EntityCombustEvent} is the figure catching fire. Damage being cancelled would leave
 *       it standing in flames for ever - burning without being hurt - which reads as a bug to
 *       everybody who walks past it.</li>
 *   <li>{@link EntityKnockbackEvent} is every shove that is <em>not</em> damage: an explosion, a
 *       piston, a wind charge. {@code setImmovable(true)} is what should stop those, and this is the
 *       cheap belt to that pair of braces - a figure that has drifted two blocks is standing inside
 *       the tavern wall. Paper's event rather than Bukkit's: {@code org.bukkit.event.entity
 *       .EntityKnockbackEvent} is deprecated for removal on 26.2 and compiling against it is a
 *       warning today and a break on the next platform bump.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> handled here: {@code PlayerInteractEntityEvent}. The right-click is the
 * whole point of the figure and is owned by {@link NpcListener}; a guard that cancelled interaction
 * would protect the NPC from being used.
 *
 * <p>{@link EventPriority#LOWEST} with {@code ignoreCancelled = false}: this is a refusal, so it
 * should be in place before anything else decides what the damage does, and a plugin that has
 * already cancelled the event agrees with us.
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
            // Cancelling stops the combustion that was about to be applied; it does not undo one
            // that already is. A figure that was alight before this handler existed - or that was
            // set alight by something that never raises this event at all - would otherwise burn
            // silently for the rest of the season.
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
