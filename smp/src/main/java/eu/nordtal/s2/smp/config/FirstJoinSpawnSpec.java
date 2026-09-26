package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** Where the first join lands, world included. */
@ConfigSpec
public interface FirstJoinSpawnSpec {

    @Order(1)
    @Name("World")
    @Key("world")
    @Comment("Which world. Normally the same name as `world-nordtal` at the top of this file.")
    @Explain(
            "Normally the same name as world-nordtal above - a second place that world name is written down, so a rename there has to be repeated here.")
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
    @Comment("Which way they face on arrival, in degrees. 0 is south, 90 west, 180 north, 270 east.")
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    @Order(6)
    @Name("Pitch")
    @Key("pitch")
    @Comment("Up or down, in degrees. 0 is level, negative looks up, 90 looks at their feet.")
    @NoExplanationNeeded
    default float pitch() {
        return 0.0f;
    }
}
