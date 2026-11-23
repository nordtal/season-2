package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Position;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** A named 3D polygon in one world, from {@code min-y} to {@code max-y} (both inclusive). */
@ConfigSpec
public interface SpawnRegionSpec {

    @Order(1)
    @Name("Name")
    @Key("name")
    @NoExplanationNeeded
    default String name() {
        return "";
    }

    @Order(2)
    @Name("World")
    @Key("world")
    @Required
    @Refers(Refers.To.WORLD)
    @NoExplanationNeeded
    default String world() {
        return "";
    }

    @Order(3)
    @Name("Y from")
    @Key("min-y")
    @NoExplanationNeeded
    default int minY() {
        return -64;
    }

    @Order(4)
    @Name("Y to")
    @Key("max-y")
    @NoExplanationNeeded
    default int maxY() {
        return 320;
    }

    @Order(5)
    @Name("Corners")
    @Key("corners")
    @Position(Position.Shape.CENTRE)
    @Explain("In order around the area, at least three. The last corner joins the first.")
    default List<CustomBorderSpec.Corner> corners() {
        return List.of();
    }
}
