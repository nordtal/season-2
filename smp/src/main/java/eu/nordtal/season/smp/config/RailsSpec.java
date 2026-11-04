package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.NotNegative;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code rails} group holds how the station menus of the rail networks look and how a minecart rides. */
@ConfigSpec
public interface RailsSpec {

    /** The stations one page of the ride menu lists; a sixth row holds the page buttons. */
    @Order(1)
    @Name("Stations per page")
    @Key("stations-per-page")
    @Between(least = 1, most = 5)
    @Explain("How many stations the menu lists on one page when a player sits down in a minecart on a station's rails.")
    default int stationsPerPage() {
        return 5;
    }

    @Order(2)
    @Name("Acceleration (blocks per second, each second)")
    @Key("acceleration")
    @Positive
    @NoExplanationNeeded
    default double acceleration() {
        return 6.0;
    }

    @Order(3)
    @Name("Top speed (blocks per second)")
    @Key("top-speed")
    @Positive
    @NoExplanationNeeded
    default double topSpeed() {
        return 24.0;
    }

    @Order(4)
    @Name("Speed through curves (blocks per second)")
    @Key("curve-speed")
    @Positive
    @NoExplanationNeeded
    default double curveSpeed() {
        return 10.0;
    }

    @Order(5)
    @Name("Speed through junctions (blocks per second)")
    @Key("junction-speed")
    @Positive
    @NoExplanationNeeded
    default double junctionSpeed() {
        return 8.0;
    }

    @Order(6)
    @Name("Braking distance (blocks)")
    @Key("braking-distance")
    @Positive
    @Explain("How far a minecart at top speed rides while it brakes to a stop. A shorter distance brakes harder.")
    default double brakingDistance() {
        return 32.0;
    }

    @Order(7)
    @Name("Already at a station within (blocks)")
    @Key("at-station-within")
    @NotNegative
    @Explain(
            "A station this close to the minecart along the rails is left out of the ride menu, because the rider is already there.")
    default int atStationWithin() {
        return 3;
    }
}
