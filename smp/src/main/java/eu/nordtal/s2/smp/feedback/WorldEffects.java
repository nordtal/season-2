package eu.nordtal.s2.smp.feedback;

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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one place in {@code smp} that names a particle or spawns a firework.
 *
 * <h2>Why one place, when there are only four moments</h2>
 * The same argument {@link eu.nordtal.s2.common.feedback.Feedback} makes about sound, made once
 * more while the answer is still small: a call site that can name a particle will eventually name a
 * different one for the same kind of moment. {@code WorldEffectVocabularyTest} in {@code :common} is
 * what keeps that true, and it was written when this file was the only one that existed - which is
 * the cheap moment, because after the first exception a rule like it is an argument rather than a
 * fact.
 *
 * <h2>Why this is code and the sounds are config, which is not an oversight</h2>
 * Ten sound categories cover some forty call sites, so {@code sounds.yml} compresses; four effects
 * cover four call sites, so a config file for them would be four entries pointing one-to-one at
 * four methods - ceremony rather than compression. The escape hatch argument is also weaker: a
 * chime repeated in a crowded tavern is what {@code sounds.yml} exists for, and a puff of cloud is
 * not. If a fifth and a sixth moment appear, that is the point at which to take the decision out
 * loud, not a filter to relax quietly. Recorded in {@code docs/presentation.md} section 6.
 *
 * <h2>The fireworks cannot hurt anybody, and that took a listener</h2>
 * A rocket carrying explosion effects deals damage where it bursts, whoever launched it. So every
 * rocket this class spawns is stamped in its persistent data and {@link #onDamage} refuses damage
 * from a stamped one - rather than relying on the burst happening far enough above somebody's head,
 * which is true until the first player standing under a ceiling. A celebration that takes four
 * hearts off the person it is celebrating is the kind of thing a season is remembered for.
 */
public final class WorldEffects implements Listener {

    /** How far from the player the milestone rockets go up, in blocks. */
    private static final double RING_RADIUS = 2.5;

    /** Three of them: enough to read as a ring, few enough that forty players is not a lag spike. */
    private static final int RING_ROCKETS = 3;

    /**
     * The palette the rockets burst in - the resource pack's own accent and highlight, plus white.
     *
     * <p>Not random colours: everything else drawn in this season comes out of
     * {@code resource-pack/tools/}'s two-colour palette, and a firework in nine unrelated hues would
     * be the one moment that looks like it came from somewhere else.
     */
    private static final List<Color> PALETTE = List.of(
            Color.fromRGB(176, 138, 74),   // accent
            Color.fromRGB(78, 86, 104),    // highlight
            Color.WHITE);

    private final NamespacedKey celebration;

    public WorldEffects(final Plugin plugin) {
        this.celebration = new NamespacedKey(plugin, "celebration");
    }

    /**
     * A milestone, around one player. Main thread.
     *
     * <p>Called once per online player rather than once for the server, because the season has no
     * single place everybody is standing - the point is that it happens where <em>you</em> are.
     */
    public void celebrate(final Player player) {
        final Location at = player.getLocation();
        for (int i = 0; i < RING_ROCKETS; i++) {
            final double angle = (2 * Math.PI * i) / RING_ROCKETS
                    + ThreadLocalRandom.current().nextDouble(0.6);
            launch(at.clone().add(Math.cos(angle) * RING_RADIUS, 0, Math.sin(angle) * RING_RADIUS));
        }
    }

    /** Somebody leaving or arriving by balloon. Main thread. */
    public void travelled(final Location at) {
        at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 30,
                0.4, 0.6, 0.4, 0.02);
    }

    /** A grave being opened, where it stands. Main thread. */
    public void graveOpened(final Location at) {
        at.getWorld().spawnParticle(Particle.SOUL, at.clone().add(0.5, 1.0, 0.5), 14,
                0.25, 0.35, 0.25, 0.01);
    }

    /** Somebody arriving on an arena platform. Main thread. */
    public void arenaEntered(final Location at) {
        at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 1, 0), 24,
                0.35, 0.5, 0.35, 0.15);
    }

    /**
     * Nothing this class launched may damage anything.
     *
     * <p>{@code LOWEST} so that a protection plugin later in the chain sees an already-cancelled
     * event rather than a live one, and {@code ignoreCancelled} left off because a damage event
     * somebody else has already cancelled costs nothing to cancel again.
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
            // One, so it bursts a second or so up rather than out of sight. Power is not the safety
            // measure here - onDamage is.
            meta.setPower(1);
            rocket.setFireworkMeta(meta);
            rocket.getPersistentDataContainer().set(celebration, PersistentDataType.BYTE, (byte) 1);
        });
    }
}
