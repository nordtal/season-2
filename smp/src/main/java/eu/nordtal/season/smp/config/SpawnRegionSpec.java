package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

@ConfigSpec
public interface SpawnRegionSpec {

    @Order(1)
    @Name("World")
    @Key("world")
    @Required
    @Refers(Refers.To.WORLD)
    @NoExplanationNeeded
    default String world() {
        return "";
    }

    @Order(2)
    @Name("Corner 1 X")
    @Key("min-x")
    @NoExplanationNeeded
    default int minX() {
        return 0;
    }

    @Order(3)
    @Name("Y from")
    @Key("min-y")
    @NoExplanationNeeded
    default int minY() {
        return -64;
    }

    @Order(4)
    @Name("Corner 1 Z")
    @Key("min-z")
    @NoExplanationNeeded
    default int minZ() {
        return 0;
    }

    @Order(5)
    @Name("Corner 2 X")
    @Key("max-x")
    @NoExplanationNeeded
    default int maxX() {
        return 0;
    }

    @Order(6)
    @Name("Y to")
    @Key("max-y")
    @NoExplanationNeeded
    default int maxY() {
        return 320;
    }

    @Order(7)
    @Name("Corner 2 Z")
    @Key("max-z")
    @NoExplanationNeeded
    default int maxZ() {
        return 0;
    }
}
