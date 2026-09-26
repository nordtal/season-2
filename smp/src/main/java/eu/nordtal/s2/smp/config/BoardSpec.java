package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

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
        "How wide the frame is drawn, in pixels of the board's own text - 32 to 240.",
        "",
        "This is a number somebody picks by looking at the board, not one the plugin can",
        "work out: the width of a line of text is decided by the vanilla font's per-",
        "character advances, which live in the client and not in this repository. A line",
        "that outgrows the frame draws over the right-hand edge, which is visible at once",
        "and is fixed here without a release. See BoardFrame."
    })
    @Explain(
            "Nobody can compute this from the text - the client owns the font's per-character widths. A line that outgrows it draws past the right edge until this is widened.")
    default int width() {
        return 180;
    }
}
