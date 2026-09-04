package eu.nordtal.s2.smp.feedback;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.feedback.FeedbackSound;
import eu.nordtal.s2.common.feedback.FeedbackSounds;
import eu.nordtal.s2.smp.config.SmpSpec;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one place in {@code smp} that names a sound to Bukkit.
 *
 * <h2>Why it lives here and not in {@code :common}</h2>
 * {@code :common} is compiled against neither Paper nor Velocity - that is a repository rule and not
 * a preference, because the same jar is shaded into a Velocity plugin. Everything about a sound that
 * can be decided without a platform is in {@code :common} already
 * ({@link Feedback}, {@link FeedbackSound}, {@link FeedbackSounds}); what is left here is one call,
 * and it is deliberately the only one in the module. {@code SoundVocabularyTest} in {@code :common}
 * fails if a second one appears anywhere in the four client-facing modules, and this file is the
 * single named exception on its allowlist.
 *
 * <p>The cost is that {@code hunger-games} will need a class of its own of about this size, and that
 * is the accepted trade: twenty lines twice, against a shared jar that references
 * {@code org.bukkit.entity.Player} inside a Velocity plugin.
 *
 * <h2>Why the String overload of playSound</h2>
 * {@code playSound(Location, String, SoundCategory, float, float)} takes the registry key straight
 * from the config, so a custom sound out of the resource pack works with no code change and a
 * vanilla one needs no lookup. The {@code Sound}-typed overloads would need a registry get, which
 * turns "the pack has not been updated yet" into a null and then into a decision this class would
 * have to take on a player's click path.
 *
 * <p>Everything here must be called from the <b>main thread</b>, like every other Bukkit call. The
 * async paths in this module already hop back to send their message; the sound goes in the same hop.
 */
public final class SmpSounds {

    private final FeedbackSounds sounds;
    private final Consumer<String> problems;

    public SmpSounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /** Reads {@code config.yml}'s {@code sounds:} block. */
    public static SmpSounds of(final SmpSpec.SoundsSpec spec, final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        for (final Feedback category : Feedback.values()) {
            final SmpSpec.SoundSpec entry = specOf(category, spec);
            declared.put(category, new FeedbackSound(
                    entry.key() == null ? "" : entry.key(), entry.volume(), entry.pitch()));
        }
        return new SmpSounds(FeedbackSounds.parse(declared, problems), problems);
    }

    /**
     * Which config entry belongs to which category.
     *
     * <p>An exhaustive {@code switch} with no {@code default}, on purpose: a category added to
     * {@link Feedback} stops this module compiling until somebody says what it sounds like, which is
     * the only mechanism that keeps the enum and the config file from drifting apart.
     */
    private static SmpSpec.SoundSpec specOf(final Feedback category, final SmpSpec.SoundsSpec spec) {
        return switch (category) {
            case SMALL_SUCCESS -> spec.smallSuccess();
            case BIG_SUCCESS -> spec.bigSuccess();
            case REFUSED -> spec.refused();
            case LOSS -> spec.loss();
            case SURFACE_OPEN -> spec.surfaceOpen();
            case SURFACE_CLOSE -> spec.surfaceClose();
            case SELECT -> spec.select();
            case TRAVEL -> spec.travel();
            case COUNTDOWN_TICK -> spec.countdownTick();
            case NETWORK_EVENT -> spec.networkEvent();
        };
    }

    /** Plays {@code category} for one player, where they are standing. Main thread. */
    public void play(final Player player, final Feedback category) {
        final FeedbackSound sound = sounds.sound(category);
        if (sound == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            // MASTER rather than a themed category: a player who has turned "Blocks" down is telling
            // the game about ambience, not about whether the server may answer their click.
            player.playSound(player.getLocation(), sound.key(), SoundCategory.MASTER,
                    sound.volume(), sound.pitch());
        } catch (final RuntimeException exception) {
            // A malformed key is refused at load, so reaching here means the platform disagreed with
            // us about something. Silence the category and say so once - a stack trace per click is
            // the only outcome worse than a missing chime.
            sounds.failed(category, exception, problems);
        }
    }

    /** Whether this category will play nothing - so a caller can skip work it would only discard. */
    public boolean isSilent(final Feedback category) {
        return sounds.isSilent(category);
    }
}
