package eu.nordtal.season.displaytags.config.spec;

import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** The {@code nametags} group: whether players wear a drawn name tag, and how it looks. */
@ConfigSpec
public interface NameTagConfigurationSpec {

    @Order(1)
    @Name("Name tags")
    @Key("enabled")
    @Explain("Off, every player shows Minecraft's own name tag.")
    default boolean enabled() {
        return true;
    }

    /** Whether a player sees their own name tag in third person. */
    @Order(2)
    @Name("Own name tag")
    @Key("show-to-self")
    @NoExplanationNeeded
    default boolean showToSelf() {
        return true;
    }

    @Order(3)
    @Name("Update interval")
    @Key("update-interval")
    @Explain("Seconds between redraws.")
    default int updateInterval() {
        return 1;
    }

    @Order(4)
    @Name("Visibility distance")
    @Key("visibility-distance")
    @Explain("In blocks.")
    default int visibilityDistance() {
        return 32;
    }

    @Order(5)
    @Name("Display")
    @Key("display")
    @NoExplanationNeeded
    default NameTagDisplayConfigurationSpec display() {
        return Specs.createDefault(NameTagDisplayConfigurationSpec.class);
    }
}
