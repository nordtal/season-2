package eu.nordtal.s2.papercommon.stage;

import eu.nordtal.s2.common.feedback.Feedback;
import org.bukkit.entity.Player;

/** A module's sound adapter, as the one method a staging needs from it, so {@code :paper-common} names no sound. */
@FunctionalInterface
public interface FeedbackPlayer {

    /** Plays {@code category} for {@code player}, where they are standing. Main thread. */
    void play(Player player, Feedback category);
}
