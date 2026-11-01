package eu.nordtal.season.smp.config;

import eu.nordtal.season.packrendering.board.BoardFrame;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

@ConfigSpec
public interface BoardSpec {

    @Order(1)
    @Name("Kind")
    @Key("kind")
    @NoExplanationNeeded
    default String kind() {
        return "OBJECTIVE";
    }

    @Order(2)
    @Name("World")
    @Key("world")
    @Required
    @Refers(Refers.To.WORLD)
    @NoExplanationNeeded
    default String world() {
        return "nordtal";
    }

    @Order(3)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 0.0;
    }

    @Order(4)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 70.0;
    }

    @Order(5)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 0.0;
    }

    @Order(6)
    @Name("Yaw")
    @Key("yaw")
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    /** The content column in pixels, bounded by the frame's shifts, which would throw on the first render. */
    @Order(7)
    @Name("Width")
    @Key("width")
    @Between(least = BoardFrame.MIN_WIDTH, most = BoardFrame.MAX_WIDTH)
    @NoExplanationNeeded
    default int width() {
        return BoardFrame.DEFAULT_WIDTH;
    }
}
