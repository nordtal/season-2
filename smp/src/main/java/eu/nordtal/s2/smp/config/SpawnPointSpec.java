package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * One landing point.
 *
 * Deliberately without a {@code world}: which world a point is in comes from the
 * {@link eu.nordtal.s2.smp.world.WorldRole} it is filed under, and a point that could name its own world could name
 * one the balloon does not fly to.
 */
@ConfigSpec
public interface SpawnPointSpec {

    @Order(1)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 0.5;
    }

    @Order(2)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 64.0;
    }

    @Order(3)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 0.5;
    }

    @Order(4)
    @Name("Yaw")
    @Key("yaw")
    @Comment("Which way they face on arrival, in degrees. 0 is south, 90 west, 180 north, 270 east.")
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    @Order(5)
    @Name("Pitch")
    @Key("pitch")
    @Comment("Up or down, in degrees. 0 is level, negative looks up, 90 looks at their feet.")
    @NoExplanationNeeded
    default float pitch() {
        return 0.0f;
    }
}
