package eu.nordtal.season.smp.feedback;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The only class in {@code smp} that names a particle or spawns a firework. Its methods run on the main thread.
 *
 * It marks every rocket it spawns, and {@link #onDamage} cancels damage from a marked rocket.
 */
public final class WorldEffects implements Listener {

    private static final double RING_RADIUS = 2.5;

    private static final int RING_ROCKETS = 3;

    /** The burst colours: accent, highlight and white. */
    private static final List<Color> PALETTE = List.of(
            Color.fromRGB(176, 138, 74), // accent
            Color.fromRGB(78, 86, 104), // highlight
            Color.WHITE);

    private final NamespacedKey celebration;

    public WorldEffects(final Plugin plugin) {
        this.celebration = new NamespacedKey(plugin, "celebration");
    }

    public void celebrate(final Player player) {
        final Location at = Objects.requireNonNull(player.getLocation());
        for (int i = 0; i < RING_ROCKETS; i++) {
            final double angle = (2 * Math.PI * i) / RING_ROCKETS
                    + ThreadLocalRandom.current().nextDouble(0.6);
            launch(at.clone().add(Math.cos(angle) * RING_RADIUS, 0, Math.sin(angle) * RING_RADIUS));
        }
    }

    public void travelled(final Location at) {
        at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);
    }

    public void graveOpened(final Location at) {
        at.getWorld().spawnParticle(Particle.SOUL, at.clone().add(0.5, 1.0, 0.5), 14, 0.25, 0.35, 0.25, 0.01);
    }

    public void arenaEntered(final Location at) {
        at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1, 0), 24, 0.35, 0.5, 0.35, 0.15);
    }

    /**
     * Cancels damage from a rocket that {@code celebrate} spawned.
     *
     * The priority is {@code LOWEST}, so later handlers see the event already cancelled.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework rocket
                && rocket.getPersistentDataContainer().has(celebration, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    private void launch(final Location at) {
        at.getWorld().spawn(at, Firework.class, rocket -> {
            final FireworkMeta meta = rocket.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(PALETTE)
                    .withFade(Color.WHITE)
                    .flicker(true)
                    .build());
            meta.setPower(1);
            rocket.setFireworkMeta(meta);
            rocket.getPersistentDataContainer().set(celebration, PersistentDataType.BYTE, (byte) 1);
        });
    }
}
