package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** Where the first join lands, world included. */
@ConfigSpec
public interface FirstJoinSpawnSpec {

    /** A rename of the Nordtal world has to be repeated here. */
    @Order(1)
    @Name("World")
    @Key("world")
    @Explain("Normally the Nordtal world's name.")
    default String world() {
        return "nordtal";
    }

    @Order(2)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 0.5;
    }

    @Order(3)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 64.0;
    }

    @Order(4)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 0.5;
    }

    @Order(5)
    @Name("Yaw")
    @Key("yaw")
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    @Order(6)
    @Name("Pitch")
    @Key("pitch")
    @NoExplanationNeeded
    default float pitch() {
        return 0.0f;
    }
}
