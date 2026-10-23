package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code npc} group places the spawn NPC, a Mannequin. A save spawns it again. */
@ConfigSpec
public interface NpcSpec {

    @Order(1)
    @Name("World")
    @Key("world")
    @Refers(Refers.To.WORLD)
    @NoExplanationNeeded
    default String world() {
        return "nordtal";
    }

    @Order(2)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 3980.0;
    }

    @Order(3)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 84.0;
    }

    @Order(4)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 2194.0;
    }

    @Order(5)
    @Name("Yaw")
    @Key("yaw")
    @NoExplanationNeeded
    default float yaw() {
        return -180.0f;
    }

    @Order(6)
    @Name("Skin name")
    @Key("skin-name")
    @NoExplanationNeeded
    default String skinName() {
        return "ohEffi";
    }
}
