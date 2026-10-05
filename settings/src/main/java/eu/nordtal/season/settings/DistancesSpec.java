package eu.nordtal.season.settings;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code distances} group: how far around a player a Paper server sends and simulates every world. */
@ConfigSpec
public interface DistancesSpec {

    @Order(1)
    @Name("View distance")
    @Key("view-distance")
    @Explain("How far a player sees, in chunks. 0 keeps this server's own value.")
    default int viewDistance() {
        return 0;
    }

    @Order(2)
    @Name("Simulation distance")
    @Key("simulation-distance")
    @Explain("How far around a player the world moves, in chunks. 0 keeps this server's own value.")
    default int simulationDistance() {
        return 0;
    }
}
