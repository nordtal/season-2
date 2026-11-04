package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The block that holds the spawn station, a rail the plugin places there and keeps. */
@ConfigSpec
public interface StationBlockSpec {

    @Order(1)
    @Name("World")
    @Key("world")
    @Required
    @Refers(Refers.To.WORLD)
    @NoExplanationNeeded
    default String world() {
        return "nordtal";
    }

    @Order(2)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default int x() {
        return 0;
    }

    @Order(3)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default int y() {
        return 64;
    }

    @Order(4)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default int z() {
        return 0;
    }
}
