package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** One landing point per world the balloon flies to. */
@ConfigSpec
public interface BalloonSpawnPointsSpec {

    @Order(1)
    @Name("Nordtal")
    @Key("nordtal")
    @Comment("Where the balloon lands in the permanent build world.")
    @NoExplanationNeeded
    default SpawnPointSpec nordtal() {
        return DefaultSmp.BALLOON_SPAWN_POINT_NORDTAL;
    }

    @Order(3)
    @Name("Nether")
    @Key("nether")
    @Comment({
        "Where it lands in the Nether. The one point with a known way to be wrong: a Y",
        "chosen without looking is inside the roof or inside solid rock."
    })
    @Explain(
            "The one point with a known way to be wrong: a Y chosen without checking the actual terrain often lands inside the Nether roof or inside solid rock.")
    default SpawnPointSpec nether() {
        return DefaultSmp.BALLOON_SPAWN_POINT_NETHER;
    }

    @Order(4)
    @Name("End")
    @Key("end")
    @Comment({
        "Where it lands in the End. The balloon is the only way in, so this is the only",
        "arrival point players ever see there."
    })
    @NoExplanationNeeded
    default SpawnPointSpec end() {
        return DefaultSmp.BALLOON_SPAWN_POINT_END;
    }
}
