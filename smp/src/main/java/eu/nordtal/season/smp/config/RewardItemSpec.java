package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Positive;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** An item granted as a reward. */
@ConfigSpec
public interface RewardItemSpec {

    @Order(1)
    @Name("Item")
    @Key("item")
    @Required
    @NoExplanationNeeded
    @Refers(Refers.To.ITEM)
    default String item() {
        return "";
    }

    @Order(2)
    @Name("Amount")
    @Key("amount")
    @Positive
    @NoExplanationNeeded
    default int amount() {
        return 1;
    }

    /** The whole item an admin took in from the game, as Base64 of its serialised stack; empty for a plain item. */
    @Order(4)
    @Name("Item data")
    @Key("data")
    @NoExplanationNeeded
    default String data() {
        return "";
    }

    @Order(5)
    @Name("Structure tag")
    @Key("structure")
    @NoExplanationNeeded
    default String structure() {
        return "";
    }
}
