package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code tunnel-miner} group configures the Survival mining speed of the Tunnel Miner enchantment. */
@ConfigSpec
public interface TunnelMinerSpec {

    double MIN_TIME_FACTOR = 0.01;
    double MAX_TIME_FACTOR = 1.0;

    @Order(1)
    @Name("Mining time factor")
    @Key("time-factor")
    @Positive
    @Explain(
            "Multiplies the combined single-block break time of the next tunnel step (0.01 to 1.0). Lower values mine faster.")
    default double timeFactor() {
        return 0.08;
    }
}
