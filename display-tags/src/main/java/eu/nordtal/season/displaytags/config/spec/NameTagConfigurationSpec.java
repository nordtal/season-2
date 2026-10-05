package eu.nordtal.season.displaytags.config.spec;

import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
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

    @Order(2)
    @Name("Own name tag")
    @Key("show-to-self")
    @Explain("Whether a player sees their own name tag in third person.")
    default boolean showToSelf() {
        return true;
    }

    @Order(3)
    @Name("Update interval")
    @Key("update-interval")
    @Explain("Seconds between two redraws of every name tag.")
    default int updateInterval() {
        return 1;
    }

    @Order(4)
    @Name("Visibility distance")
    @Key("visibility-distance")
    @Explain("Blocks from which a name tag can be seen.")
    default int visibilityDistance() {
        return 32;
    }

    @Order(5)
    @Name("Display")
    @Key("display")
    @Explain("How the text display above a player looks.")
    default NameTagDisplayConfigurationSpec display() {
        return Specs.createDefault(NameTagDisplayConfigurationSpec.class);
    }
}
