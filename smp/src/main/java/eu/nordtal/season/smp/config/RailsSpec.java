package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.Entries;
import eu.nordtal.season.settings.rule.NotNegative;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

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

    @Order(4)
    @Name("Stop before a junction (blocks)")
    @Key("junction-stop")
    @Between(least = 0, most = 16)
    @Explain("How far before a junction set the wrong way a minecart stops, while the junction is switched for it.")
    default int junctionStop() {
        return 2;
    }

    /** The spawn station stands at this block; without an entry there is none. */
    @Order(20)
    @Name("Spawn station")
    @Key("spawn-station")
    @Entries(most = 1)
    @Explain("The block of the spawn station, which the plugin places and keeps there. Leave it empty for none.")
    default List<StationBlockSpec> spawnStation() {
        return List.of();
    }
}
