package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Longest;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** A kit two fighters can agree on: its name, the item that shows it and what each fighter gets. */
@ConfigSpec
public interface DuelKitSpec {

    /** The aura history stores the kit by its English name, in a column of 64 characters. */
    @Order(1)
    @Name("Name (English)")
    @Key("name")
    @Required
    @Longest(64)
    @NoExplanationNeeded
    default String name() {
        return "";
    }

    @Order(2)
    @Name("Name (German)")
    @Key("name-de")
    @Longest(64)
    @Explain("Leave it empty to show the English name to German players too.")
    default String nameDe() {
        return "";
    }

    @Order(3)
    @Name("Icon")
    @Key("icon")
    @Refers(Refers.To.ITEM)
    @NoExplanationNeeded
    default String icon() {
        return "minecraft:iron_sword";
    }

    @Order(4)
    @Name("Loadout")
    @Key("loadout")
    @NoExplanationNeeded
    default List<DuelLoadoutItemSpec> loadout() {
        return List.of();
    }
}
