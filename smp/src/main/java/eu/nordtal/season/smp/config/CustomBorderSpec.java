package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Position;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.settings.rule.Unique;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code custom-border} group: a wall along polygons per world, beside the vanilla border. A save redraws it. */
@ConfigSpec
public interface CustomBorderSpec {

    @Order(1)
    @Name("Worlds")
    @Key("worlds")
    @Unique("world")
    @NoExplanationNeeded
    default List<World> worlds() {
        return List.of();
    }

    @ConfigSpec
    interface World {

        @Order(1)
        @Name("World")
        @Key("world")
        @Required
        @Refers(Refers.To.WORLD)
        @NoExplanationNeeded
        default String world() {
            return "";
        }

        @Order(2)
        @Name("Enabled")
        @Key("enabled")
        @Explain("Shows the wall and pushes players back at it. Off leaves only the vanilla border.")
        default boolean enabled() {
            return false;
        }

        @Order(3)
        @Name("Areas")
        @Key("polygons")
        @Explain("Players stay inside these areas. Where two areas touch, the wall between them is left out.")
        default List<Polygon> polygons() {
            return List.of();
        }
    }

    @ConfigSpec
    interface Polygon {

        @Order(1)
        @Name("Name")
        @Key("name")
        @NoExplanationNeeded
        default String name() {
            return "";
        }

        @Order(2)
        @Name("Corners")
        @Key("corners")
        @Position(Position.Shape.CENTRE)
        @Explain("In order around the area, at least three. The last corner joins the first.")
        default List<Corner> corners() {
            return List.of();
        }
    }

    @ConfigSpec
    interface Corner {

        @Order(1)
        @Name("X")
        @Key("x")
        @NoExplanationNeeded
        default int x() {
            return 0;
        }

        @Order(2)
        @Name("Z")
        @Key("z")
        @NoExplanationNeeded
        default int z() {
            return 0;
        }
    }
}
