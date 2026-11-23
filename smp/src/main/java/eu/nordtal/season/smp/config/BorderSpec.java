package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Position;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.Map;

/** The {@code border} group sizes and centres the world borders. A save re-applies them. */
@ConfigSpec
public interface BorderSpec {

    /** The vanilla border of Nordtal while no overworld region of the custom border is open. */
    @Order(5)
    @Name("Border diameter before the start")
    @Key("start-border-diameter")
    @Positive
    @NoExplanationNeeded
    default int startBorderDiameter() {
        return 20;
    }

    @Order(6)
    @Name("Nether border diameter")
    @Key("nether-border-diameter")
    @Positive
    @NoExplanationNeeded
    default int netherBorderDiameter() {
        return 5000;
    }

    @Order(7)
    @Name("End border diameter")
    @Key("end-border-diameter")
    @Positive
    @NoExplanationNeeded
    default int endBorderDiameter() {
        return 5000;
    }

    @Order(8)
    @Name("Border centre")
    @Key("border-centre")
    @Position(Position.Shape.CENTRE)
    @NoExplanationNeeded
    default CentreSpec borderCentre() {
        return Specs.createUnsafe(CentreSpec.class, Map.of("x", 3979, "z", 2188));
    }

    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @Positive
    @NoExplanationNeeded
    default double borderExpansionBlocksPerSecond() {
        return 1.0;
    }
}
