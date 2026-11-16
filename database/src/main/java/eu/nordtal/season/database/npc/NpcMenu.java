package eu.nordtal.season.database.npc;

import eu.nordtal.season.database.npc.Dialogue.Words;
import java.util.Objects;

/** One registered Nordtal dialog menu a dialogue may open. */
public record NpcMenu(String key, Words hint) {

    public NpcMenu {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(hint, "hint");
    }
}
