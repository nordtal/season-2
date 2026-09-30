package eu.nordtal.s2.smp.stage;

import eu.nordtal.s2.messages.feedback.Feedback;
import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/**
 * One player's screen, as far as a staging is concerned.
 *
 * Tracked by UUID, since a captured {@code Player} of somebody who reconnected stays offline for ever.
 */
public final class PlayerStage implements CinematicStage {

    /** How much longer than its own length a frame stays, so scheduler jitter cannot open a gap between frames. */
    private static final Duration OVERHANG = Duration.ofMillis(500);

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final UUID who;
    private final FeedbackPlayer sounds;

    /** What was actually applied, so {@link #clear()} removes that and not a guess. */
    private @Nullable PotionEffectType applied;

    public PlayerStage(final Plugin plugin, final UUID who, final FeedbackPlayer sounds) {
        this.plugin = plugin;
        this.who = who;
        this.sounds = sounds;
    }

    @Override
    public void show(final Component image, final @Nullable Component subtitle, final int ticks) {
        final Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        player.showTitle(Title.title(
                image,
                subtitle == null ? Component.empty() : subtitle,
                Title.Times.times(
                        Duration.ZERO,
                        Duration.ofMillis(ticks * MILLIS_PER_TICK).plus(OVERHANG),
                        Duration.ZERO)));
    }

    @Override
    public void effect(final Cinematic.Effect effect, final int ticks) {
        final Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        final PotionEffectType type = resolve(effect.type());
        if (type == null) {
            return;
        }
        applied = type;
        // No ambient, particles or icon: this is a picture.
        player.addPotionEffect(new PotionEffect(type, ticks, effect.amplifier(), false, false, false));
    }

    @Override
    public void play(final Feedback sound) {
        final Player player = Bukkit.getPlayer(who);
        if (player != null) {
            sounds.play(player, sound);
        }
    }

    @Override
    public void clear() {
        final Player player = Bukkit.getPlayer(who);
        if (player == null) {
            // Nothing to clean up: the title and effect were saved with the player who left.
            return;
        }
        player.clearTitle();
        if (applied != null) {
            // Removes the whole effect, so nobody comes out of this still blind.
            player.removePotionEffect(applied);
            applied = null;
        }
    }

    /** Returns the effect this server knows by that key, or {@code null} with one warning; never fatal on a join. */
    private @Nullable PotionEffectType resolve(final String type) {
        try {
            final PotionEffectType resolved = Registry.MOB_EFFECT.get(Key.key(type));
            if (resolved == null) {
                plugin.getLogger()
                        .warning("a staged moment asked for the potion effect '" + type
                                + "', which this server does not know - it runs without the effect");
            }
            return resolved;
        } catch (final RuntimeException failure) {
            plugin.getLogger()
                    .warning("a staged moment asked for the potion effect '" + type
                            + "', which is not a namespaced key (" + failure + ") - it runs without the"
                            + " effect");
            return null;
        }
    }
}
