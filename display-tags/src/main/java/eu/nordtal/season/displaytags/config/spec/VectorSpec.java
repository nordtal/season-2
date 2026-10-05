package eu.nordtal.season.displaytags.config.spec;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

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
