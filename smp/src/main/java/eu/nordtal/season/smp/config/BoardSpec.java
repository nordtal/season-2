package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.Comment;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** One board's anchor: which board, where it hangs, and which way it faces. */
@ConfigSpec
public interface BoardSpec {

    @Order(1)
    @Name("Kind")
    @Key("kind")
    @Comment("OBJECTIVE or AURA.")
    @NoExplanationNeeded
    default String kind() {
        return "OBJECTIVE";
    }

    @Order(2)
    @Name("World")
    @Key("world")
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
    @Comment("Which way the board faces, in degrees. 0 is south, 90 west, 180 north, 270 east.")
    @NoExplanationNeeded
    default float yaw() {
        return 0.0f;
    }

    @Order(7)
    @Name("Width")
    @Key("width")
    @Comment({
        "How wide the frame is drawn, in pixels of the board's own text, 32 to 240.",
        "Picked by eye: the client owns the font's widths. A line that outgrows it draws past the edge."
    })
    @Explain("The client owns the font's per-character widths, so this is picked by looking at the board.")
    default int width() {
        return 180;
    }
}
