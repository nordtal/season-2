package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.NotNegative;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

@ConfigSpec
public interface AdvancementAwardSpec {

    @Order(1)
    @Name("Advancement")
    @Key("advancement")
    @Required
    @NoExplanationNeeded
    @Refers(Refers.To.ADVANCEMENT)
    default String advancement() {
        return "";
    }

    /** The band keeps one advancement from outweighing a whole objective. */
    @Order(2)
    @Name("Aura")
    @Key("aura")
    @Between(least = 2, most = 10)
    @NoExplanationNeeded
    default int aura() {
        return 2;
    }

    @Order(3)
    @Name("Spins")
    @Key("spins")
    @NotNegative
    @NoExplanationNeeded
    default int spins() {
        return 0;
    }
}
