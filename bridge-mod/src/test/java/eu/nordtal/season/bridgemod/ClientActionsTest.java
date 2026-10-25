package eu.nordtal.season.bridgemod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers.PendingComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientActionsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The inventory menu is 0 and a server counts its menus from 1 to 100, so a click can reach neither. */
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 6})
    void theChestHasAContainerIdThatNeitherTheInventoryNorAServerUses(final int rows) {
        final int id = ClientActions.chestMenu(new Inventory(null, new EntityEquipment()), rows).containerId;
        assertFalse(id >= 0 && id <= 100, id + " is an id the game uses.");
    }

    @Test
    void giveTakesAStackAndRefusesACountAboveTheStackSize() {
        final HolderLookup.Provider registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(PendingComponents::apply);
        assertEquals(
                16, ClientActions.stack(registries, "minecraft:ender_pearl", 16).getCount());
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> ClientActions.stack(registries, "minecraft:ender_pearl", 17));
        assertTrue(String.valueOf(refused.getMessage()).contains("16"), refused.getMessage());
    }
}
