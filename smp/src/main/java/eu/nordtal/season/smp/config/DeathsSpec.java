package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code deaths-and-graves} group holds the death penalties and how long a grave lasts. */
@ConfigSpec
public interface DeathsSpec {

    @Order(28)
    @Name("Death penalty")
    @Key("death-penalty")
    @NoExplanationNeeded
    default int deathPenalty() {
        return 5;
    }

    @Order(29)
    @Name("Death penalty (listed causes)")
    @Key("death-penalty-listed")
    @NoExplanationNeeded
    default int deathPenaltyListed() {
        return 20;
    }

    @Order(30)
    @Name("Listed death causes")
    @Key("death-causes-listed")
    @NoExplanationNeeded
    @Refers(Refers.To.DAMAGE_TYPE)
    default List<String> deathCausesListed() {
        return List.of(
                "minecraft:lava",
                "minecraft:in_fire",
                "minecraft:on_fire",
                "minecraft:cactus",
                "minecraft:drown",
                "minecraft:in_wall",
                "minecraft:sweet_berry_bush",
                "minecraft:hot_floor",
                "minecraft:campfire",
                "minecraft:stalagmite");
    }

    @Order(44)
    @Name("Grave max age (hours)")
    @Key("grave-max-age-hours")
    @NoExplanationNeeded
    default int graveMaxAgeHours() {
        return 24;
    }
}
