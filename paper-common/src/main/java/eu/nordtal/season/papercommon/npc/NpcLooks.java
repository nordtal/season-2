package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Npc;
import eu.nordtal.season.database.npc.NpcSkin;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.npc.DialogueScreen;
import eu.nordtal.season.packrendering.npc.DialogueScreen.Speaker;
import java.util.Locale;
import net.kyori.adventure.key.Key;

/** The resource-pack keys of one NPC's body texture and portrait font, which steward-agent writes into the pack. */
final class NpcLooks {

    private NpcLooks() {}

    /** Returns the font of the NPC's portrait: its own with an uploaded skin, the pack's default otherwise. */
    static String portraitFont(final Npc npc) {
        return npc.skin() == null ? Glyphs.FONT_NPC : NpcSkin.portraitFont(npc.id());
    }

    /** Returns who speaks in the NPC's dialogue for a reader of {@code locale}: its name, its tab and its portrait. */
    static Speaker speaker(final Npc npc, final Locale locale) {
        return new Speaker(npc.name().in(locale), DialogueScreen.DEFAULT_TAB, null, portraitFont(npc));
    }

    /** Returns the texture of the NPC's body, which only an NPC with an uploaded skin has. */
    static Key body(final Npc npc) {
        return Key.key(NpcSkin.bodyTexture(npc.id()));
    }
}
