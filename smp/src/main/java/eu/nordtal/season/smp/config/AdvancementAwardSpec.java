package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** One advancement and what it pays. */
@ConfigSpec
public interface AdvancementAwardSpec {

    @Order(1)
    @Name("Advancement")
    @Key("advancement")
    @NoExplanationNeeded
    @Refers(Refers.To.ADVANCEMENT)
    default String advancement() {
        return "";
    }

    @Order(2)
    @Name("Aura")
    @Key("aura")
    @NoExplanationNeeded
    default int aura() {
        return 2;
    }
}
