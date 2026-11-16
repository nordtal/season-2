package eu.nordtal.season.database.npc;

import java.util.Objects;
import java.util.UUID;

/**
 * One immutable uploaded Minecraft skin, with its PNG encoded as Base64.
 * steward-agent puts it into the resource pack under keys of the NPC that wears it, which the game names.
 */
public record NpcSkin(UUID id, SkinModel model, String png) {

    /** Returns the pack's texture of the body of the NPC called {@code npc}, without its folder and extension. */
    public static String bodyTexture(final UUID npc) {
        return "nordtal:npc/" + npc + "/skin";
    }

    /** Returns the pack's font that draws the portrait of the NPC called {@code npc}. */
    public static String portraitFont(final UUID npc) {
        return "nordtal:npc/" + npc;
    }

    public NpcSkin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(png, "png");
    }

    /** The arm shape the skin was drawn for. */
    public enum SkinModel {
        CLASSIC,
        SLIM
    }
}
