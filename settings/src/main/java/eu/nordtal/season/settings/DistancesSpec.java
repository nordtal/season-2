package eu.nordtal.season.settings;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code distances} group: how far around a player a Paper server sends and simulates every world. */
@ConfigSpec
public interface DistancesSpec {

    /** In chunks. 0 keeps this server's own value. */
    @Order(1)
    @Name("View distance")
    @Key("view-distance")
    @NoExplanationNeeded
    default int viewDistance() {
        return 0;
    }

    /** In chunks. 0 keeps this server's own value. */
    @Order(2)
    @Name("Simulation distance")
    @Key("simulation-distance")
    @NoExplanationNeeded
    default int simulationDistance() {
        return 0;
    }
}
