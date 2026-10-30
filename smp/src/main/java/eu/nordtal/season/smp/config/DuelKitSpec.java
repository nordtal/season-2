package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** A kit two fighters can agree on: its name, the item that shows it and what each fighter gets. */
@ConfigSpec
public interface DuelKitSpec {

    @Order(1)
    @Name("Name")
    @Key("name")
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
