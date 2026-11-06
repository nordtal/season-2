package eu.nordtal.season.packrendering.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.value.GameContent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The table that :resource-pack writes from the client jar of the season's Minecraft version. */
class ItemSpritesTest {

    @Test
    void aFlatItemDrawsFromTheItemAtlasAndABlockFromTheBlockAtlas() {
        assertEquals(
                Optional.of(new GameContent.Sprite("minecraft:items", "minecraft:item/diamond")),
                ItemSprites.of("minecraft:diamond"));
        assertEquals(
                Optional.of(new GameContent.Sprite("minecraft:blocks", "minecraft:block/oak_log")),
                ItemSprites.of("minecraft:oak_log"));
    }

    /** Tinted, special and changing items would draw grey, wrongly or as the missing texture, so they have none. */
    @Test
    void anItemWhoseSpriteWouldDrawWronglyIsNotInTheTable() {
        for (final String item : List.of("grass_block", "oak_leaves", "chest", "compass", "clock", "potion")) {
            assertEquals(Optional.empty(), ItemSprites.of("minecraft:" + item), item);
        }
    }

    @Test
    void everySpriteLiesInTheItemOrTheBlockAtlasUnderItsOwnFolder() {
        assertTrue(ItemSprites.table().size() > 1000, "the table holds most items");
        ItemSprites.table().forEach((item, sprite) -> {
            assertTrue(Set.of("minecraft:items", "minecraft:blocks").contains(sprite.atlas()), item);
            final String folder = sprite.atlas().equals("minecraft:items") ? ":item/" : ":block/";
            assertTrue(sprite.sprite().contains(folder), item + " draws " + sprite.sprite());
        });
    }
}
