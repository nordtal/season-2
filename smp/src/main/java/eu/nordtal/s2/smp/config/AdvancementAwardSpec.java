package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/** One advancement and what it pays. */
@ConfigSpec
public interface AdvancementAwardSpec {

    @Order(1)
    @Name("Advancement")
    @Key("advancement")
    @Comment("The advancement key, e.g. minecraft:story/mine_diamond.")
    @NoExplanationNeeded
    default String advancement() {
        return "";
    }

    @Order(2)
    @Name("Aura")
    @Key("aura")
    @Comment("2 to 10. Anything outside that band stops the load.")
    @NoExplanationNeeded
    default int aura() {
        return 2;
    }
}
