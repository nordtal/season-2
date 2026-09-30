package eu.nordtal.s2.proxy.feedback;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.messages.feedback.FeedbackSound;
import eu.nordtal.s2.proxy.command.CommandGate;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * The one place in {@code proxy} that names a sound to Velocity.
 *
 * Call it from Velocity's event thread, the one {@code CommandGate#onCommandExecute} runs on.
 */
public final class ProxySounds implements CommandGate.Chime {

    private final FeedbackSounds sounds;
    private final Consumer<String> problems;

    ProxySounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /**
     * The one sound this module plays: {@link Feedback#REFUSED}, at the key and pitch smp's {@code sounds.yml} ships.
     *
     * @param problems where a value that had to be ignored is reported, once each
     */
    public static ProxySounds defaults(final Consumer<String> problems) {
        final Map<Feedback, FeedbackSound> declared = new EnumMap<>(Feedback.class);
        declared.put(Feedback.REFUSED, new FeedbackSound("minecraft:block.note_block.bass", 1.0f, 0.7f));
        return new ProxySounds(FeedbackSounds.parse(declared, problems), problems);
    }

    @Override
    public void play(final Player player, final Feedback category) {
        final FeedbackSound sound = sounds.sound(category);
        if (sound == null || player == null) {
            return;
        }
        try {
            // MASTER: this is not ambience a client's sliders are meant for.
            player.playSound(Sound.sound(Key.key(sound.key()), Sound.Source.MASTER, sound.volume(), sound.pitch()));
        } catch (final RuntimeException exception) {
            // A malformed key is refused at load, so the platform disagreed about something.
            sounds.failed(category, exception, problems);
        }
    }
}
