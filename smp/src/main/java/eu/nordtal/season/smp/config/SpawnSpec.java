package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Position;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code spawn} group holds the Nordtal spawn and the protected areas. */
@ConfigSpec
public interface SpawnSpec {

    @Order(43)
    @Name("Nordtal spawn")
    @Key("nordtal-spawn")
    @Position(Position.Shape.SPOT)
    @NoExplanationNeeded
    default SpotSpec nordtalSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    @Order(27)
    @Name("Protected areas")
    @Key("protected-areas")
    @Position(Position.Shape.AREA)
    @NoExplanationNeeded
    default List<SpawnRegionSpec> protectedAreas() {
        return DefaultSmp.SPAWN_REGIONS;
    }
}
