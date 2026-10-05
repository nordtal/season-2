package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.Comment;
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
    @Comment("An item key, such as minecraft:cooked_beef.")
    @NoExplanationNeeded
    @Refers(Refers.To.ITEM)
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
