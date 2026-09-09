package eu.nordtal.s2.papercommon.stage;

import eu.nordtal.s2.common.feedback.Feedback;

import org.bukkit.entity.Player;

/**
 * A module's sound adapter, as the one method a staging needs from it.
 *
 * <p>{@code SmpSounds::play} is one, and so is whatever {@code hunger-games} hands in. Taking the
 * adapter behind this interface rather than by its type is what keeps {@code :paper-common} from
 * naming a sound at all - the rule {@code SoundVocabularyTest} enforces for the four client-facing
 * modules, kept here because a shared layer breaking it would break it for all of them at once.
 *
 * <p>It also keeps the blank-key rule where it already lives: a category with no key in
 * {@code sounds.yml} plays nothing, decided once in {@code FeedbackSounds} and not re-decided here.
 */
@FunctionalInterface
public interface FeedbackPlayer {

    /** Plays {@code category} for {@code player}, where they are standing. Main thread. */
    void play(Player player, Feedback category);
}
