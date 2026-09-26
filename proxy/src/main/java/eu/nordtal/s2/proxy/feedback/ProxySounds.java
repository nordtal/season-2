package eu.nordtal.s2.proxy.feedback;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.feedback.FeedbackSound;
import eu.nordtal.s2.common.feedback.FeedbackSounds;
import eu.nordtal.s2.proxy.command.CommandGate;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * The one place in {@code proxy} that names a sound to Velocity.
 *
 * Lives here and not in {@code :common} for the same reason {@code SmpSounds} and
 * {@code HungerGamesSounds} live in their own modules: {@code :common} is compiled against neither
 * Paper nor Velocity, so the platform call ({@code Player#playSound}) has to sit next to whichever
 * plugin makes it. {@code SoundVocabularyTest} in {@code :common} fails the build if a second
 * sound-playing file appears anywhere in the four client-facing modules.
 *
 * Unlike {@code smp} and {@code hunger-games}, which each read a {@code sounds.yml} an operator can
 * retune by ear, this module has no such file: {@code CommandGate} only ever needs one category,
 * {@link Feedback#REFUSED}, and {@code network.yml} carries no sound configuration to read one
 * from. The declared table below is therefore a constant rather than a parsed config, using the
 * same {@code minecraft:block.note_block.bass} at pitch 0.7 that {@code smp} and
 * {@code hunger-games} already use for the same category. A future category only needs another
 * entry in the map below; a config file to retune them by ear is a separate change.
 *
 * Everything here must be called from Velocity's own event thread, the same thread
 * {@code CommandGate#onCommandExecute} already runs on - there is no second hop to get wrong.
 */
public final class ProxySounds implements CommandGate.Chime {

    private final FeedbackSounds sounds;
    private final Consumer<String> problems;

    ProxySounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /**
     * The one sound this module plays today: {@link Feedback#REFUSED}.
     *
     * At the same key and pitch {@code smp} and {@code hunger-games} declare in their shipped {@code sounds.yml}.
     *
     * @param problems where a value that had to be ignored is reported, once each. A plugin passes
     *                 {@code logger::warn}
     */
    public static ProxySounds defaults(final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        declared.put(Feedback.REFUSED, new FeedbackSound("minecraft:block.note_block.bass", 1.0f, 0.7f));
        return new ProxySounds(FeedbackSounds.parse(declared, problems), problems);
    }

    /** Plays {@code category} for one player, wherever on the network they are standing. */
    @Override
    public void play(final Player player, final Feedback category) {
        final FeedbackSound sound = sounds.sound(category);
        if (sound == null || player == null) {
            return;
        }
        try {
            // MASTER rather than a themed category: this is not the kind of ambience a client's sliders are for.
            player.playSound(Sound.sound(Key.key(sound.key()), Sound.Source.MASTER, sound.volume(), sound.pitch()));
        } catch (final RuntimeException exception) {
            // A malformed key is refused at load, so reaching here means the platform disagreed about something.
            sounds.failed(category, exception, problems);
        }
    }
}
