package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** Where the spawn NPC stands, what it is called, and whose skin it wears. */
@ConfigSpec
public interface NpcSpec {

    @Order(1)
    @Name("World")
    @Key("world")
    @NoExplanationNeeded
    default String world() {
        return "nordtal";
    }

    @Order(2)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 106.5;
    }

    @Order(3)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 68.0;
    }

    @Order(4)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 92.5;
    }

    @Order(5)
    @Name("Yaw")
    @Key("yaw")
    @NoExplanationNeeded
    default float yaw() {
        return 180.0f;
    }

    @Order(6)
    @Name("Skin name")
    @Key("skin-name")
    @NoExplanationNeeded
    default String skinName() {
        return "";
    }

    @Order(7)
    @Name("Name")
    @Key("name")
    @NoExplanationNeeded
    default String name() {
        return "Nordtal";
    }
}
