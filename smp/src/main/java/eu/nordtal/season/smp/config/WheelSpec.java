package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code wheel} group holds the prize pool and the blocks a player spins at. Each spin reads it. */
@ConfigSpec
public interface WheelSpec {

    @Order(25)
    @Name("Wheel regions")
    @Key("wheel-regions")
    @NoExplanationNeeded
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    @Order(37)
    @Name("Wheel prizes")
    @Key("wheel-prizes")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }
}
