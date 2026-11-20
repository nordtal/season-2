package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** A prize the wheel lands on with a chance of its weight over the pool's total weight. */
@ConfigSpec
public interface WheelPrizeSpec extends RewardItemSpec {

    @Order(3)
    @Name("Weight")
    @Key("weight")
    @Positive
    @NoExplanationNeeded
    default int weight() {
        return 1;
    }
}
