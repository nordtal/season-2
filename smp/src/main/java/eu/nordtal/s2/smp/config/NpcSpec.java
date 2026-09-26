package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

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
    @Comment("Which way it faces, in degrees. 0 is south, 90 west, 180 north, 270 east.")
    @NoExplanationNeeded
    default float yaw() {
        return 180.0f;
    }

    @Order(6)
    @Name("Skin name")
    @Key("skin-name")
    @Comment("A Minecraft account name whose skin to wear, or empty for the default.")
    @NoExplanationNeeded
    default String skinName() {
        return "";
    }

    @Order(7)
    @Name("Name")
    @Key("name")
    @Comment("The label above it. Empty for none.")
    @NoExplanationNeeded
    default String name() {
        return "Nordtal";
    }
}
