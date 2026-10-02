package eu.nordtal.s2.papercommon.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

/** That a namespaced key and the Bukkit name of the same thing read alike, and anything else as nothing. */
class GameKeysTest {

    @Test
    void aKeyIsLowerCaseAndInMinecraftUnlessItNamesAnother() {
        assertEquals("minecraft:oak_log", GameKeys.key(" OAK_LOG "));
        assertEquals("minecraft:oak_log", GameKeys.key("minecraft:oak_log"));
        assertEquals("nordtal:relic", GameKeys.key("nordtal:Relic"));
        assertEquals("", GameKeys.key(" "));
    }

    @Test
    void aMaterialReadsFromItsKeyAndItsBukkitName() {
        assertEquals(Optional.of(Material.OAK_LOG), GameKeys.material("minecraft:oak_log"));
        assertEquals(Optional.of(Material.OAK_LOG), GameKeys.material("oak_log"));
        assertEquals(Optional.of(Material.OAK_LOG), GameKeys.material("OAK_LOG"));
        assertEquals(Optional.empty(), GameKeys.material("nordtal:oak_log"));
        assertEquals(Optional.empty(), GameKeys.material("oak_logs"));
    }

    @Test
    void aStatisticAndAnEntityTypeReadFromTheirKeys() {
        assertEquals(Optional.of(Statistic.MINE_BLOCK), GameKeys.statistic("minecraft:mine_block"));
        assertEquals(Optional.of(Statistic.MINE_BLOCK), GameKeys.statistic("MINE_BLOCK"));
        assertEquals(Optional.empty(), GameKeys.statistic("minecraft:mined"));
        assertEquals(Optional.of(EntityType.ZOMBIE), GameKeys.entity("minecraft:zombie"));
        assertEquals(Optional.of(EntityType.ZOMBIE), GameKeys.entity("ZOMBIE"));
        assertEquals(Optional.empty(), GameKeys.entity("unknown"));
    }
}
