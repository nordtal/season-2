package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** A column of a world, such as the centre of its border. */
@ConfigSpec
public interface CentreSpec {

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
