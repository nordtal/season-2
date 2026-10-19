package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code spawn} group holds the first-join spawn and the protected spawn regions. */
@ConfigSpec
public interface SpawnSpec {

    @Order(43)
    @Name("First join spawn")
    @Key("first-join-spawn")
    @NoExplanationNeeded
    default FirstJoinSpawnSpec firstJoinSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    @Order(27)
    @Name("Spawn regions")
    @Key("spawn-regions")
    @NoExplanationNeeded
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }
}
