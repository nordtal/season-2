package eu.nordtal.season.database.npc;

import eu.nordtal.season.database.npc.Dialogue.Words;
import eu.nordtal.season.database.npc.NpcSkin.SkinModel;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A figure an admin placed, with its look, its voice and its dialogue.
 * {@code artwork} names an uploaded picture that the dialogue's portrait shows in place of the bust of its skin.
 */
public record Npc(
        UUID id,
        Words name,
        Place place,
        Pose pose,
        float pitch,
        Map<Slot, Equipment> equipment,
        Dialogue dialogue,
        @Nullable UUID skin,
        SkinModel skinModel,
        Plate plate,
        @Nullable UUID artwork) {

    /** Fills what a version stored before plates and artwork held: the default plate and no artwork. */
    public Npc {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(place, "place");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(dialogue, "dialogue");
        equipment = Map.copyOf(equipment);
        skinModel = skinModel == null ? SkinModel.CLASSIC : skinModel;
        plate = plate == null ? Plate.DEFAULT : plate;
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
        this(id, name, place, pose, pitch, equipment, dialogue, null, SkinModel.CLASSIC, Plate.DEFAULT, null);
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
                skin == null ? SkinModel.CLASSIC : skin.model(),
                plate,
                artwork);
    }

    /** Returns this NPC with {@code dialogue}. */
    public Npc withDialogue(final Dialogue dialogue) {
        return new Npc(id, name, place, pose, pitch, equipment, dialogue, skin, skinModel, plate, artwork);
    }

    /** Returns this NPC with {@code plate} and the portrait {@code artwork}, or its skin's bust for null. */
    public Npc withLook(final Plate plate, final @Nullable UUID artwork) {
        return new Npc(id, name, place, pose, pitch, equipment, dialogue, skin, skinModel, plate, artwork);
    }

    /** The name plate above its head, which also colours the name tab of its dialogue. */
    public record Plate(
            PlateStyle style, PlateColour colour, @Nullable PlateIcon icon) {

        /** A blue pill without a symbol, as every NPC had before plates could be chosen. */
        public static final Plate DEFAULT = new Plate(PlateStyle.PILL, PlateColour.BLUE, null);

        public Plate {
            style = style == null ? PlateStyle.PILL : style;
            colour = colour == null ? PlateColour.BLUE : colour;
        }
    }

    /** How a name plate is drawn. */
    public enum PlateStyle {
        /** A rounded box in the colour. */
        PILL,
        /** A dark box with a stripe of the colour at its left end. */
        DARK,
        /** A banner in the colour with notched ends. */
        RIBBON,
        /** A round badge in the colour that holds the symbol, before a dark box. */
        BADGE
    }

    /** The colours of name plates and tabs, each dark enough to carry white bold text. */
    public enum PlateColour {
        BLUE(0x345AB4),
        RED(0xA83232),
        AMBER(0x8A5A00),
        GREEN(0x2E7032),
        TEAL(0x00707A),
        VIOLET(0x6B44A8),
        ROSE(0xA63A6E),
        SLATE(0x4A5468);

        private final int rgb;

        PlateColour(final int rgb) {
            this.rgb = rgb;
        }

        /** Returns the colour as 0xRRGGBB. */
        public int rgb() {
            return rgb;
        }
    }

    /** The symbols of the season pack a name plate and tab can show before the name. */
    public enum PlateIcon {
        STAR,
        PIN,
        RAIL,
        MEDAL,
        FLAG,
        WHEEL,
        SWORDS,
        SHIELD,
        HAND_IN,
        CHEST,
        EYE,
        PICKAXE,
        BOX,
        HEART
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
