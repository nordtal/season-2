package eu.nordtal.season.limbo.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The {@code config} group: the waiting room's settings.
 *
 * The waiting reason is not here: the proxy sends it on {@code nordtal:limbo}, so the title has one source.
 */
@ConfigSpec
public interface LimboSpec {

    @Order(1)
    @Name("World name")
    @Key("world-name")
    @Explain(
            "The empty world this plugin creates and puts everybody in; safe to delete, it is rebuilt on the next start.")
    default String worldName() {
        return "limbo";
    }

    @Order(2)
    @Name("Spawn Y")
    @Key("spawn-y")
    @Explain("How far above the void players stand, so a stray fall lands them back rather than into nothing.")
    default int spawnY() {
        return 64;
    }

    @Order(3)
    @Name("Title refresh (seconds)")
    @Key("title-refresh-seconds")
    @Explain(
            "How often the waiting title is re-sent; too infrequent and the screen goes black between refreshes, looking like a crash.")
    default int titleRefreshSeconds() {
        return 4;
    }

    @Order(4)
    @Name("Blindness")
    @Key("blindness")
    @Explain(
            "Turns the empty world's sky into a black screen. Disable only while working on this plugin, never in production.")
    default boolean blindness() {
        return true;
    }
}
