package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

@ConfigSpec
public interface BoardSpec {

    @Order(1)
    @Name("Kind")
    @Key("kind")
    @NoExplanationNeeded
    default String kind() {
        return "OBJECTIVE";
    }

    @Order(2)
    @Name("World")
    @Key("world")
    @NoExplanationNeeded
    default String world() {
        return "nordtal";
    }

    @Order(3)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 0.0;
    }

    @Order(4)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 70.0;
    }

    @Order(5)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 0.0;
    }

    @Order(6)
    @Name("Yaw")
    @Key("yaw")
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    @Order(7)
    @Name("Width")
    @Key("width")
    @NoExplanationNeeded
    default int width() {
        return 180;
    }
}
