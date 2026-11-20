package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcSkin;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Icon;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Speaker;
import eu.nordtal.season.packrendering.npc.NpcPlate;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextColor;

/**
 * How one NPC looks: its plate as chosen in Steward, and the pack keys of its body texture and portrait font.
 * steward-agent writes both into the pack.
 */
public final class NpcLooks {

    private NpcLooks() {}

    /** Returns the plate above the NPC's head in its style, colour and icon. */
    public static NpcPlate.Look plate(final Npc npc) {
        final Npc.Plate plate = npc.plate();
        final Npc.PlateIcon icon = plate.icon();
        return new NpcPlate.Look(style(plate.style()), colour(plate), icon == null ? null : symbol(icon));
    }

    /** Returns the NPC's portrait font: its own with an uploaded skin or picture, the pack's default otherwise. */
    static String portraitFont(final Npc npc) {
        return npc.skin() == null && npc.artwork() == null ? Glyphs.FONT_NPC : NpcSkin.portraitFont(npc.id());
    }

    /**
     * Returns who speaks in the NPC's dialogue for a reader of {@code locale}.
     * That is its name, its tab in the plate's colour with the plate's icon, and its portrait.
     */
    static Speaker speaker(final Npc npc, final Locale locale) {
        final Npc.PlateIcon icon = npc.plate().icon();
        return new Speaker(
                npc.name().in(locale), colour(npc.plate()), icon == null ? null : tabIcon(icon), portraitFont(npc));
    }

    /** Returns the texture of the NPC's body, which only an NPC with an uploaded skin has. */
    static Key body(final Npc npc) {
        return Key.key(NpcSkin.bodyTexture(npc.id()));
    }

    private static TextColor colour(final Npc.Plate plate) {
        return TextColor.color(plate.colour().rgb());
    }

    private static NpcPlate.Style style(final Npc.PlateStyle style) {
        return switch (style) {
            case PILL -> NpcPlate.Style.PILL;
            case DARK -> NpcPlate.Style.DARK;
            case RIBBON -> NpcPlate.Style.RIBBON;
            case BADGE -> NpcPlate.Style.BADGE;
        };
    }

    private static Symbol symbol(final Npc.PlateIcon icon) {
        return switch (icon) {
            case STAR -> Symbol.STAR;
            case PIN -> Symbol.PIN;
            case RAIL -> Symbol.RAIL;
            case MEDAL -> Symbol.MEDAL;
            case FLAG -> Symbol.FLAG;
            case WHEEL -> Symbol.WHEEL;
            case SWORDS -> Symbol.SWORDS;
            case SHIELD -> Symbol.SHIELD;
            case HAND_IN -> Symbol.HANDIN;
            case CHEST -> Symbol.CHEST;
            case EYE -> Symbol.EYE;
            case PICKAXE -> Symbol.PICKAXE;
            case BOX -> Symbol.BOX;
            case HEART -> Symbol.HEART;
        };
    }

    private static Icon tabIcon(final Npc.PlateIcon icon) {
        return switch (icon) {
            case STAR -> Icon.STAR;
            case PIN -> Icon.PIN;
            case RAIL -> Icon.RAIL;
            case MEDAL -> Icon.MEDAL;
            case FLAG -> Icon.FLAG;
            case WHEEL -> Icon.WHEEL;
            case SWORDS -> Icon.SWORDS;
            case SHIELD -> Icon.SHIELD;
            case HAND_IN -> Icon.HAND_IN;
            case CHEST -> Icon.CHEST;
            case EYE -> Icon.EYE;
            case PICKAXE -> Icon.PICKAXE;
            case BOX -> Icon.BOX;
            case HEART -> Icon.HEART;
        };
    }
}
