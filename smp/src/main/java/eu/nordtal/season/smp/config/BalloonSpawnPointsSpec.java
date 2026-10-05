package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** One landing point per world the balloon flies to. */
@ConfigSpec
public interface BalloonSpawnPointsSpec {

    @Order(1)
    @Name("Nordtal")
    @Key("nordtal")
    @NoExplanationNeeded
    default SpawnPointSpec nordtal() {
        return DefaultSmp.BALLOON_SPAWN_POINT_NORDTAL;
    }

    @Order(3)
    @Name("Nether")
    @Key("nether")
    @Explain("A Y chosen without checking the terrain often lands inside the Nether roof or inside solid rock.")
    default SpawnPointSpec nether() {
        return DefaultSmp.BALLOON_SPAWN_POINT_NETHER;
    }

    @Order(4)
    @Name("End")
    @Key("end")
    @NoExplanationNeeded
    default SpawnPointSpec end() {
        return DefaultSmp.BALLOON_SPAWN_POINT_END;
    }
}
