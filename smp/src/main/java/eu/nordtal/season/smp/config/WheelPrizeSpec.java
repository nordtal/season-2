package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** One item with an amount: a prize of the wheel, or a piece of a duel loadout. */
@ConfigSpec
public interface WheelPrizeSpec {

    @Order(1)
    @Name("Item")
    @Key("item")
    @NoExplanationNeeded
    @Refers(Refers.To.ITEM)
    default String item() {
        return "";
    }

    @Order(2)
    @Name("Amount")
    @Key("amount")
    @NoExplanationNeeded
    default int amount() {
        return 1;
    }

    @Order(3)
    @Name("Weight")
    @Key("weight")
    @NoExplanationNeeded
    default int weight() {
        return 1;
    }
}
