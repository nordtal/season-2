package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** One protected box. */
@ConfigSpec
public interface SpawnRegionSpec {

    @Order(1)
    @Name("World")
    @Key("world")
    @Comment("Which world the box is in.")
    @NoExplanationNeeded
    default String world() {
        return "";
    }

    @Order(2)
    @Name("Min X")
    @Key("min-x")
    @NoExplanationNeeded
    default int minX() {
        return 0;
    }

    @Order(3)
    @Name("Min Y")
    @Key("min-y")
    @NoExplanationNeeded
    default int minY() {
        return -64;
    }

    @Order(4)
    @Name("Min Z")
    @Key("min-z")
    @NoExplanationNeeded
    default int minZ() {
        return 0;
    }

    @Order(5)
    @Name("Max X")
    @Key("max-x")
    @NoExplanationNeeded
    default int maxX() {
        return 0;
    }

    @Order(6)
    @Name("Max Y")
    @Key("max-y")
    @NoExplanationNeeded
    default int maxY() {
        return 320;
    }

    @Order(7)
    @Name("Max Z")
    @Key("max-z")
    @NoExplanationNeeded
    default int maxZ() {
        return 0;
    }
}
