package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Longest;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** A kit two fighters can agree on: its name, the item that shows it and what each fighter gets. */
@ConfigSpec
public interface DuelKitSpec {

    /** The aura history stores the kit by name, in a column of 64 characters. */
    @Order(1)
    @Name("Name")
    @Key("name")
    @Required
    @Longest(64)
    @NoExplanationNeeded
    default String name() {
        return "";
    }

    @Order(2)
    @Name("Icon")
    @Key("icon")
    @Refers(Refers.To.ITEM)
    @NoExplanationNeeded
    default String icon() {
        return "minecraft:iron_sword";
    }

    @Order(3)
    @Name("Loadout")
    @Key("loadout")
    @NoExplanationNeeded
    default List<DuelLoadoutItemSpec> loadout() {
        return List.of();
    }
}
