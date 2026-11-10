package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.menu.ItemSprites;
import eu.nordtal.season.papercommon.game.GameKeys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrackItemSpritesTest {

    /** A track names its items as Bukkit names or namespaced keys, and both find the item's sprite. */
    @ParameterizedTest
    @ValueSource(strings = {"DIAMOND", "minecraft:diamond", "OAK_LOG"})
    void aTracksItemNameFindsItsSprite(final String name) {
        assertTrue(ItemSprites.of(GameKeys.key(name)).isPresent(), name);
    }
}
