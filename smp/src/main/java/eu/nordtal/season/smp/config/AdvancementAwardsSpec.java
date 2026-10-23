package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code advancement-awards} group pays aura and spins for advancements. Each advancement reads it. */
@ConfigSpec
public interface AdvancementAwardsSpec {

    @Order(33)
    @Name("Advancement awards")
    @Key("advancement-awards")
    @NoExplanationNeeded
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }
}
