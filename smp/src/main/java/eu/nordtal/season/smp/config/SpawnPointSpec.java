package eu.nordtal.season.smp.config;

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
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    @Order(5)
    @Name("Pitch")
    @Key("pitch")
    @NoExplanationNeeded
    default float pitch() {
        return 0.0f;
    }
}
