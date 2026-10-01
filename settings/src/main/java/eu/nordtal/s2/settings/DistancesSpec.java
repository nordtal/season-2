package eu.nordtal.s2.settings;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** {@code distances.yml}: how far around a player a Paper server sends and simulates every world. */
@ConfigSpec(
        header = {
            "View and simulation distance",
            "",
            "How many chunks around a player this server sends and simulates, in every world.",
            "Applied at start and on every reload. 0 leaves the value this server runs with",
            "anyway; anything else is taken into 2 to 32, the range Paper accepts.",
            "",
            "Every setting can be overridden with an environment variable: this service's",
            "prefix, then _DISTANCES_ and the setting."
        })
public interface DistancesSpec {

    @Order(1)
    @Name("View distance")
    @Key("view-distance")
    @Comment("Chunks sent to a player in every direction. 0 leaves this server's own value.")
    @Explain("How far a player sees, in chunks. 0 keeps this server's own value.")
    default int viewDistance() {
        return 0;
    }

    @Order(2)
    @Name("Simulation distance")
    @Key("simulation-distance")
    @Comment("Chunks around a player in which mobs move and crops grow. 0 leaves this server's own value.")
    @Explain("How far around a player the world moves, in chunks. 0 keeps this server's own value.")
    default int simulationDistance() {
        return 0;
    }
}
