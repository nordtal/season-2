package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.rule.NotNegative;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code rails} group holds how a minecart rides on the rail networks and which stations it is already at. */
@ConfigSpec
public interface RailsSpec {

    @Order(1)
    @Name("Acceleration (blocks per second, each second)")
    @Key("acceleration")
    @Positive
    @Explain("How fast a minecart speeds up, and how fast it brakes for a stop ahead.")
    default double acceleration() {
        return 6.0;
    }

    @Order(2)
    @Name("Top speed (blocks per second)")
    @Key("top-speed")
    @Positive
    @NoExplanationNeeded
    default double topSpeed() {
        return 24.0;
    }

    @Order(3)
    @Name("Station width (blocks)")
    @Key("station-width")
    @NotNegative
    @Explain("A station this close along the rails is left out of the ride menu: the rider is already there.")
    default int stationWidth() {
        return 3;
    }
}
