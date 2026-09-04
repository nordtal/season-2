package eu.nordtal.s2.hungergames.feedback;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.feedback.FeedbackSound;
import eu.nordtal.s2.common.feedback.FeedbackSounds;
import eu.nordtal.s2.hungergames.config.SoundsSpec;

import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one place in {@code hunger-games} that names a sound to Bukkit.
 *
 * <h2>Why it lives here and not in {@code :common}</h2>
 * {@code :common} is compiled against neither Paper nor Velocity - that is a repository rule and not
 * a preference, because the same jar is shaded into a Velocity plugin. Everything about a sound that
 * can be decided without a platform is in {@code :common} already
 * ({@link Feedback}, {@link FeedbackSound}, {@link FeedbackSounds}); what is left here is one call.
 * {@code SmpSounds} predicted this class by name and called twenty lines twice the accepted trade
 * against a shared jar that references {@code org.bukkit.entity.Player} inside a Velocity plugin -
 * this is that second twenty lines, and it is deliberately a near-copy rather than an abstraction.
 *
 * <p>{@code SoundVocabularyTest} in {@code :common} fails if a second sound-playing file appears
 * anywhere in the four client-facing modules; this file is one of its two named exceptions.
 *
 * <h2>Why the String overload of playSound</h2>
 * {@code playSound(Location, String, SoundCategory, float, float)} takes the registry key straight
 * from the config, so a custom sound out of the resource pack works with no code change and a
 * vanilla one needs no lookup. The {@code Sound}-typed overloads would need a registry get, which
 * turns "the pack has not been updated yet" into a null and then into a decision this class would
 * have to take on a player's death path.
 *
 * <p>Everything here must be called from the <b>main thread</b>, like every other Bukkit call. The
 * async paths in this module - the admin gate, the death handler, {@code /hg ready} - all already
 * hop back to send their message; the sound goes in that same hop and never in a second one.
 */
public final class HungerGamesSounds {

    /**
     * Volatile because {@code /hg reload} replaces it in the middle of a game.
     *
     * <p>One reference swap rather than a mutable map: a reload has to be all-or-nothing, and every
     * listener in this plugin holds the same {@code HungerGamesSounds} instance from enable to
     * disable. A death that lands mid-reload therefore hears either the whole old file or the whole
     * new one.
     */
    private volatile FeedbackSounds sounds;

    private final Consumer<String> problems;

    public HungerGamesSounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /** Reads {@code sounds.yml}. */
    public static HungerGamesSounds of(final SoundsSpec spec, final Consumer<String> problems) {
        return new HungerGamesSounds(parse(spec, problems), problems);
    }

    /**
     * Re-reads an already-reloaded {@code sounds.yml}, after {@code /hg reload}.
     *
     * <p>This is what the whole file being separate from {@code config.yml} buys. {@code /hg reload}
     * deliberately re-reads no game parameter at all - a border schedule must not move while players
     * are running from it - and a sound is the one setting that has to be changeable in the middle
     * of the one hour a year this server is actually being used.
     *
     * <p>A category that had been switched off by {@link FeedbackSounds#failed} comes back, which is
     * correct: the operator has just said what they want the sound to be, and if it still throws it
     * will switch itself off again on the first play.
     */
    public void reload(final SoundsSpec spec) {
        this.sounds = parse(spec, problems);
    }

    private static FeedbackSounds parse(final SoundsSpec spec, final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        for (final Feedback category : Feedback.values()) {
            final SoundsSpec.SoundSpec entry = specOf(category, spec);
            declared.put(category, new FeedbackSound(
                    entry.key() == null ? "" : entry.key(), entry.volume(), entry.pitch()));
        }
        return FeedbackSounds.parse(declared, problems);
    }

    /**
     * Which config entry belongs to which category.
     *
     * <p>An exhaustive {@code switch} with no {@code default}, on purpose: a category added to
     * {@link Feedback} stops this module compiling until somebody says what it sounds like, which is
     * the only mechanism that keeps the enum and the config file from drifting apart. Three of the
     * ten are never played on this server and are listed here anyway for exactly that reason.
     */
    private static SoundsSpec.SoundSpec specOf(final Feedback category, final SoundsSpec spec) {
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

    /**
     * Plays {@code category} for one player, where they are standing. Main thread.
     *
     * <p>{@code player} may be null, and callers rely on that: on this server the participant a
     * sound belongs to is regularly offline - an armor-stand body standing in for somebody who
     * disconnected is still a participant that can be killed, and its owner has no client to hear
     * anything. Making every call site check would be fifteen null checks for one rule.
     */
    public void play(final Player player, final Feedback category) {
        // One read of the volatile field, used for both the lookup and the failure. A reload
        // replaces the whole registry, and hunger-games reloads it off the main thread, so two reads
        // could take the sound from the old configuration and stamp the failure on the new one -
        // silencing a category in a file that never produced the bad key. Found by review,
        // 2026-09-04; smp's copy is written the same way, where the reload happens to be on the
        // main thread, because "correct only because of where the caller runs" is not a property
        // worth relying on twice.
        final FeedbackSounds current = sounds;
        final FeedbackSound sound = current.sound(category);
        if (sound == null || player == null || !player.isOnline()) {
            return;
        }
        try {
            // MASTER rather than a themed category: a player who has turned "Blocks" down is telling
            // the game about ambience, not about whether the server may tell them they are out.
            player.playSound(player.getLocation(), sound.key(), SoundCategory.MASTER,
                    sound.volume(), sound.pitch());
        } catch (final RuntimeException exception) {
            // A malformed key is refused at load, so reaching here means the platform disagreed with
            // us about something. Silence the category and say so once - a stack trace per death is
            // the only outcome worse than a missing chime.
            current.failed(category, exception, problems);
        }
    }

    /** Whether this category will play nothing - so a caller can skip work it would only discard. */
    public boolean isSilent(final Feedback category) {
        return sounds.isSilent(category);
    }
}
