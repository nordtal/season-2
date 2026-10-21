package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** An item that a duel loadout gives each player, in this amount. */
@ConfigSpec
public interface DuelLoadoutItemSpec {

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
}
