package eu.nordtal.season.smp.stage;

import eu.nordtal.season.messages.feedback.Feedback;
import org.bukkit.entity.Player;

@FunctionalInterface
public interface FeedbackPlayer {

    /** Plays {@code category} to {@code player} where the player stands. Main thread only. */
    void play(Player player, Feedback category);
}
