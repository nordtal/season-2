package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** One prize, or one item of the winner's head start. */
@ConfigSpec
public interface WheelPrizeSpec {

    @Order(1)
    @Name("Item")
    @Key("item")
    @Comment("A Bukkit material name.")
    @NoExplanationNeeded
    default String item() {
        return "";
    }

    @Order(2)
    @Name("Amount")
    @Key("amount")
    @Comment("How many.")
    @NoExplanationNeeded
    default int amount() {
        return 1;
    }

    @Order(3)
    @Name("Weight")
    @Key("weight")
    @Comment("Relative weight. Ignored for the winner's head start, which is not drawn.")
    @NoExplanationNeeded
    default int weight() {
        return 1;
    }
}
