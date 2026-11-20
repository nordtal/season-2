package eu.nordtal.season.smp.trader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class PlantTradesTest {

    @Test
    void bothPlantsAreAddedAlongsideVanillaOffers() {
        assertEquals(List.of(Material.TALL_GRASS, Material.RED_SHRUB), PlantTrades.missingFrom(Set.of(Material.FERN)));
    }

    @Test
    void existingPlantTradesAreKeptWithoutDuplicates() {
        assertEquals(List.of(Material.RED_SHRUB), PlantTrades.missingFrom(Set.of(Material.TALL_GRASS)));
    }
}
