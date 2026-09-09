package eu.nordtal.s2.papercommon.stage;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.stage.Cinematic;
import eu.nordtal.s2.common.stage.CinematicStage;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Duration;
import java.util.UUID;

/**
 * One player's screen, as far as a staging is concerned.
 *
 * <h2>By UUID, never by the {@code Player} it was built from</h2>
 * A staging lasts seconds and every one of its frames is a scheduled task. In that time the player
 * can leave and come back, and a captured {@code Player} instance of somebody who reconnected
 * answers {@code isOnline()} false for ever - so the frames would silently stop for somebody who is
 * standing right there. The same trap {@code HeadStart} was corrected for on 2026-09-08.
 *
 * <h2>The title's own timing carries the frame, and that is what makes it an animation</h2>
 * A title with a fade is a title that dissolves between frames, which at twenty ticks a picture
 * reads as flickering rather than as movement. Fade in and fade out are therefore zero and the stay
 * is the frame's own length plus a small overhang, so the next {@code showTitle} replaces this one
 * while it is still fully drawn. The overhang is why nothing here has to be exact: a frame that is
 * one tick late overlaps rather than leaving a gap of black.
 */
public final class PlayerStage implements CinematicStage {

    /**
     * How much longer than its own length a frame stays on screen.
     *
     * <p>Half a second. Long enough that a tick of scheduler jitter cannot open a hole between two
     * frames, short enough that the last frame is gone about when the staging is - and
     * {@link #clear()} takes it off exactly on time anyway.
     */
    private static final Duration OVERHANG = Duration.ofMillis(500);

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final UUID who;
    private final FeedbackPlayer sounds;

    /** What was actually applied, so {@link #clear()} removes that and not a guess. */
    private PotionEffectType applied;

    public PlayerStage(final Plugin plugin, final UUID who, final FeedbackPlayer sounds) {
        this.plugin = plugin;
        this.who = who;
        this.sounds = sounds;
    }

    @Override
    public void show(final Component image, final Component subtitle, final int ticks) {
        final Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        player.showTitle(Title.title(
                image,
                subtitle == null ? Component.empty() : subtitle,
                Title.Times.times(Duration.ZERO,
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
        // ambient false, particles false, icon false: this is a picture, and a staging that also
        // decorates the player with swirling motes and a status icon in the corner is a staging
        // with the plumbing showing.
        player.addPotionEffect(new PotionEffect(type, ticks, effect.amplifier(), false, false,
                false));
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
            // Nothing to clean up on a player who is not here: the title is client state that goes
            // with the session, and a potion effect on a player who left is already saved with them.
            // Which is exactly why Cinematics cancels on quit BEFORE the player is gone.
            return;
        }
        player.clearTitle();
        if (applied != null) {
            // Removes the whole effect and not only our share of it, so a player who was blind for
            // some other reason comes out of this able to see. Between "the staging removed a
            // blindness it did not apply" and "the staging left somebody blind", only one of the two
            // is discovered by the person it happened to.
            player.removePotionEffect(applied);
            applied = null;
        }
    }

    /**
     * A namespaced key to the effect this server knows, or {@code null} with one warning.
     *
     * <p>Never fatal: a staging is decoration, and a typo in an effect name must not be able to
     * throw on a join. The same rule {@code FeedbackSounds} applies to a sound key.
     */
    private PotionEffectType resolve(final String type) {
        try {
            final PotionEffectType resolved = Registry.MOB_EFFECT.get(Key.key(type));
            if (resolved == null) {
                plugin.getLogger().warning("a staged moment asked for the potion effect '" + type
                        + "', which this server does not know - it runs without the effect");
            }
            return resolved;
        } catch (final RuntimeException failure) {
            plugin.getLogger().warning("a staged moment asked for the potion effect '" + type
                    + "', which is not a namespaced key (" + failure + ") - it runs without the"
                    + " effect");
            return null;
        }
    }
}
