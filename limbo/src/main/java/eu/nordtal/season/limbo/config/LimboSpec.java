package eu.nordtal.season.limbo.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The {@code config} group: the waiting room's settings.
 *
 * The waiting reason is not here: the proxy sends it on {@code nordtal:limbo}, so the title has one source.
 */
@ConfigSpec
public interface LimboSpec {

    /**
     * The empty world this plugin creates and puts everybody in; deleting it is safe, it is rebuilt on the next start.
     */
    @Order(1)
    @Name("World name")
    @Key("world-name")
    @NoExplanationNeeded
    default String worldName() {
        return "limbo";
    }

    /** How far above the void players stand, so a stray fall lands them back rather than into nothing. */
    @Order(2)
    @Name("Spawn Y")
    @Key("spawn-y")
    @NoExplanationNeeded
    default int spawnY() {
        return 64;
    }

    /** How often the waiting title is re-sent. */
    @Order(3)
    @Name("Title refresh (seconds)")
    @Key("title-refresh-seconds")
    @Explain("Too long and the screen goes black between refreshes.")
    default int titleRefreshSeconds() {
        return 4;
    }

    /** Turns the empty world's sky into a black screen. */
    @Order(4)
    @Name("Blindness")
    @Key("blindness")
    @Explain("Disable only while developing, never in production.")
    default boolean blindness() {
        return true;
    }
}
