package eu.nordtal.season.steward.npc;

import eu.nordtal.season.database.npc.NpcSkin;
import java.io.IOException;

/** Makes immutable NPC skins from an upload or from a Minecraft account's current skin. */
public interface NpcSkinSource {

    /** Checks an uploaded PNG and returns it as a new skin; refuses anything but a 64 by 64 PNG. */
    NpcSkin uploaded(byte[] bytes, NpcSkin.SkinModel model) throws IOException;

    /**
     * Copies the current skin of the account {@code player} names, by its name or its UUID.
     * The copy never follows later changes of the account.
     *
     * @throws UnknownPlayer when no account has that name or UUID
     * @throws NoSkin when the account wears the default skin or a skin older than 64 by 64
     * @throws IOException when Mojang does not answer with the skin
     */
    NpcSkin copy(String player) throws IOException;

    /** No Minecraft account has the name or UUID asked for. */
    final class UnknownPlayer extends IOException {

        private static final long serialVersionUID = 1L;

        public UnknownPlayer(final String player) {
            super("no Minecraft account is called " + player);
        }
    }

    /** The account has no skin of its own that a figure can wear. */
    final class NoSkin extends IOException {

        private static final long serialVersionUID = 1L;

        public NoSkin(final String player) {
            super(player + " has no skin of its own to copy");
        }
    }

    /** An upload that is not a 64 by 64 PNG of at most {@link NpcSkins#LIMIT} bytes. */
    final class NotASkin extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public NotASkin(final String why) {
            super(why);
        }
    }
}
