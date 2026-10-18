package eu.nordtal.season.proxy.feedback;

import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.messagerendering.feedback.FeedbackSounds;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.feedback.FeedbackSound;
import eu.nordtal.season.proxy.command.CommandGate;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/** Plays the sounds of the proxy. Call it on the event thread of Velocity. */
public final class ProxySounds implements CommandGate.Chime {

    private final FeedbackSounds sounds;
    private final Consumer<String> problems;

    ProxySounds(final FeedbackSounds sounds, final Consumer<String> problems) {
        this.sounds = sounds;
        this.problems = problems;
    }

    /**
     * Creates the sounds with {@link Feedback#REFUSED}, the only sound of the proxy.
     *
     * @param problems receives each ignored value once
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
            player.playSound(Sound.sound(Key.key(sound.key()), Sound.Source.MASTER, sound.volume(), sound.pitch()));
        } catch (final RuntimeException exception) {
            sounds.failed(category, exception, problems);
        }
    }
}
