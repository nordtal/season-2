package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** One item with an amount: a prize of the wheel, or a piece of a duel loadout. */
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
    @Comment("Relative weight on the wheel. A duel loadout, which is not drawn, ignores it.")
    @NoExplanationNeeded
    default int weight() {
        return 1;
    }
}
