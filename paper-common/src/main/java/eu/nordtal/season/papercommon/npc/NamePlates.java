package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Npc;
import org.bukkit.entity.Mannequin;

/** Draws the name above each figure; a plugin without name tags draws none. */
public interface NamePlates {

    NamePlates NONE = new NamePlates() {
        @Override
        public void attach(final Mannequin figure, final Npc npc) {}

        @Override
        public void detach(final Mannequin figure) {}
    };

    /** Draws the name of {@code npc} above its new {@code figure}. */
    void attach(Mannequin figure, Npc npc);

    /** Removes the name above {@code figure} before the figure goes. */
    void detach(Mannequin figure);
}
