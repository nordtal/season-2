package eu.nordtal.season.database.npc;

import java.util.Objects;
import java.util.UUID;

/**
 * One immutable uploaded portrait picture, a square PNG encoded as Base64.
 * steward-agent draws it into the NPC's portrait in the resource pack, in place of the bust of its skin.
 */
public record NpcArtwork(UUID id, String png) {

    public NpcArtwork {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(png, "png");
    }
}
