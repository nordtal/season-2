package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.packrendering.npc.DialogueScreen;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a dialogue shows on one tick, and what it asks of the game.
 * {@code zoom} runs from 0, the normal view, to 1, the full zoom.
 */
public record Frame(
        int bars,
        int vignette,
        double zoom,
        DialogueScreen.@Nullable Panel panel,
        List<DialogueScreen.Hint> hints,
        DialogueScreen.@Nullable Switch toggle,
        List<Effect> effects) {

    public Frame {
        hints = List.copyOf(hints);
        effects = List.copyOf(effects);
    }

    /** Returns whether {@code other} draws the same screen. */
    public boolean drawsLike(final Frame other) {
        return bars == other.bars
                && Objects.equals(panel, other.panel)
                && hints.equals(other.hints)
                && Objects.equals(toggle, other.toggle);
    }
}
