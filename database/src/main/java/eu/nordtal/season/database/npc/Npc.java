package eu.nordtal.season.database.npc;

import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.NpcSkin.SkinModel;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A figure an admin placed, with its look, its voice and its dialogue. */
public record Npc(
        UUID id,
        Words name,
        Place place,
        Pose pose,
        float pitch,
        Map<Slot, Equipment> equipment,
        Dialogue dialogue,
        @Nullable UUID skin,
        SkinModel skinModel) {

    public Npc {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(dialogue, "dialogue");
        equipment = Map.copyOf(equipment);
        skinModel = skinModel == null ? SkinModel.CLASSIC : skinModel;
    }

    /** Creates an NPC with the default mannequin skin. */
    public Npc(
            final UUID id,
            final Words name,
            final Place place,
            final Pose pose,
            final float pitch,
            final Map<Slot, Equipment> equipment,
            final Dialogue dialogue) {
        this(id, name, place, pose, pitch, equipment, dialogue, null, SkinModel.CLASSIC);
    }

    /** Returns this NPC with {@code skin}, or the default skin when it is null. */
    public Npc withSkin(final @Nullable NpcSkin skin) {
        return new Npc(
                id,
                name,
                place,
                pose,
                pitch,
                equipment,
                dialogue,
                skin == null ? null : skin.id(),
                skin == null ? SkinModel.CLASSIC : skin.model());
    }

    /** Returns this NPC with {@code dialogue}. */
    public Npc withDialogue(final Dialogue dialogue) {
        return new Npc(id, name, place, pose, pitch, equipment, dialogue, skin, skinModel);
    }

    /** Where it stands, and which way it faces in degrees. */
    public record Place(String world, double x, double y, double z, float yaw) {

        public Place {
            Objects.requireNonNull(world, "world");
        }
    }

    /** How it holds itself. */
    public enum Pose {
        STAND,
        SIT,
        CROUCH
    }

    /** Where it wears or holds an item. */
    public enum Slot {
        HEAD,
        CHEST,
        LEGS,
        FEET,
        HAND,
        OFF_HAND
    }

    /** An item taken in from the game: its key, and the whole stack as Base64 of its serialised bytes. */
    public record Equipment(String item, String data) {

        public Equipment {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(data, "data");
        }
    }
}
