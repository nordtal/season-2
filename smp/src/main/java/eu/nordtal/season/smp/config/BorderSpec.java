package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code border} group sizes and centres the world borders. A save re-applies them. */
@ConfigSpec
public interface BorderSpec {

    @Order(6)
    @Name("Nether border diameter")
    @Key("nether-border-diameter")
    @NoExplanationNeeded
    default int netherBorderDiameter() {
        return 2000;
    }

    @Order(7)
    @Name("End border diameter")
    @Key("end-border-diameter")
    @NoExplanationNeeded
    default int endBorderDiameter() {
        return 2000;
    }

    @Order(8)
    @Name("Border centre X")
    @Key("border-centre-x")
    @NoExplanationNeeded
    default int borderCentreX() {
        return 3979;
    }

    @Order(9)
    @Name("Border centre Z")
    @Key("border-centre-z")
    @NoExplanationNeeded
    default int borderCentreZ() {
        return 2188;
    }

    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @NoExplanationNeeded
    default double borderExpansionBlocksPerSecond() {
        return 1.0;
    }
}
