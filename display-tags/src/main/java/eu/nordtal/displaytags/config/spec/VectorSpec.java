package eu.nordtal.displaytags.config.spec;

import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** Three numbers along the axes, in blocks. */
@ConfigSpec
public interface VectorSpec {

    @Order(1)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default double x() {
        return 0.0;
    }

    @Order(2)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default double y() {
        return 0.0;
    }

    @Order(3)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default double z() {
        return 0.0;
    }
}
