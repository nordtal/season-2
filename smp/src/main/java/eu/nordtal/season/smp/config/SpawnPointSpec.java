package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.Comment;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** One landing point, without a world: the {@link eu.nordtal.season.smp.world.WorldRole} it is filed under names it. */
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
