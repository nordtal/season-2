package eu.nordtal.season.limbo.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code config} group of limbo. */
@ConfigSpec
public interface LimboSpec {

    /** The name of the empty world that limbo creates on start. */
    @Order(1)
    @Name("World name")
    @Key("world-name")
    @NoExplanationNeeded
    default String worldName() {
        return "limbo";
    }

    /** The Y coordinate of the spawn. */
    @Order(2)
    @Name("Spawn Y")
    @Key("spawn-y")
    @NoExplanationNeeded
    default int spawnY() {
        return 64;
    }

    /** The seconds between two sends of the title. */
    @Order(3)
    @Name("Title refresh (seconds)")
    @Key("title-refresh-seconds")
    @Explain("Too long and the screen goes black between refreshes, which looks like a crash.")
    default int titleRefreshSeconds() {
        return 4;
    }

    /** Gives every player the blindness effect. */
    @Order(4)
    @Name("Blindness")
    @Key("blindness")
    @Explain("Off only while developing, never in production.")
    default boolean blindness() {
        return true;
    }
}
