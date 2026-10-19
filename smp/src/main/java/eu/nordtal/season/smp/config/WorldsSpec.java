package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/**
 * The {@code worlds} group names the worlds and the datapacks.
 * It applies at the next start, because a running server keeps the worlds and datapacks it loaded.
 */
@ConfigSpec
public interface WorldsSpec {

    @Order(1)
    @Name("Nordtal world")
    @Key("world-nordtal")
    @NoExplanationNeeded
    default String worldNordtal() {
        return "nordtal";
    }

    @Order(3)
    @Name("Nether world")
    @Key("world-nether")
    @NoExplanationNeeded
    default String worldNether() {
        return "nordtal_nether";
    }

    @Order(4)
    @Name("End world")
    @Key("world-end")
    @NoExplanationNeeded
    default String worldEnd() {
        return "nordtal_the_end";
    }

    @Order(14)
    @Name("Required datapacks")
    @Key("required-datapacks")
    @Explain("Start-up fails if these data packs are not installed.")
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }
}
