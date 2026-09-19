package eu.nordtal.s2.proxy.feedback;

import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.feedback.FeedbackSound;
import eu.nordtal.s2.common.feedback.FeedbackSounds;
import eu.nordtal.s2.proxy.command.CommandGate;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one place in {@code proxy} that names a sound to Velocity.
 *
 * <h2>Why it lives here and not in {@code :common}</h2>
 * The same reason {@code SmpSounds} and {@code HungerGamesSounds} live in their own modules:
 * {@code :common} is compiled against neither Paper nor Velocity, so the platform call
 * ({@code Player#playSound}) has to sit next to whichever plugin makes it. {@code SoundVocabularyTest}
 * in {@code :common} fails the build if a second sound-playing file appears anywhere in the four
 * client-facing modules; this file is the third named exception, added in season-2-ingame/28.
 *
 * <h2>What is different here, and why</h2>
 * {@code smp} and {@code hunger-games} each read a {@code sounds.yml} an operator can retune by ear
 * while players are online. This module has no such file yet - {@code CommandGate} only ever needs
 * one category, {@link Feedback#REFUSED}, and {@code network.yml} carries no sound configuration to
 * read one from. The declared table below is therefore a constant rather than a parsed config, using
 * the same {@code minecraft:block.note_block.bass} at pitch 0.7 that {@code smp} and {@code
 * hunger-games} already use for the same category - one refusal sound across the whole network,
 * unless an operator has reason to want a different one on the proxy specifically. A future category
 * only needs another entry in the map below; a config file to retune them by ear is a separate
 * change, for whenever the proxy grows a second sound.
 *
 * <p>Everything here must be called from Velocity's own event thread, the same thread
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
     * The one sound this module plays today: {@link Feedback#REFUSED}, at the same key and pitch
     * {@code smp} and {@code hunger-games} declare in their shipped {@code sounds.yml}.
     *
     * @param problems where a value that had to be ignored is reported, once each. A plugin passes
     *                 {@code logger::warn}
     */
    public static ProxySounds defaults(final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        declared.put(Feedback.REFUSED,
                new FeedbackSound("minecraft:block.note_block.bass", 1.0f, 0.7f));
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
            // MASTER rather than a themed category, same reasoning as SmpSounds: whether the server
            // may answer a refused command is not the kind of ambience a client's volume sliders
            // are for.
            player.playSound(Sound.sound(Key.key(sound.key()), Sound.Source.MASTER, sound.volume(),
                    sound.pitch()));
        } catch (final RuntimeException exception) {
            // A malformed key is refused at load, so reaching here means the platform disagreed
            // with us about something. Silence the category and say so once - the same rule
            // FeedbackSounds documents for every other adapter.
            sounds.failed(category, exception, problems);
        }
    }
}
