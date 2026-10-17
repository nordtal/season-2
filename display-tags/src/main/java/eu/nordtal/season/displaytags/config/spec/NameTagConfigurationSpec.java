package eu.nordtal.season.displaytags.config.spec;

import eu.nordtal.season.displaytags.SeeThroughMode;
import eu.nordtal.season.displaytags.wrapper.display.TextAlignment;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Display;

/** The {@code nametags} group: how the name tag drawn above every player looks. */
@ConfigSpec
public interface NameTagConfigurationSpec {

    /** Whether a player sees their own name tag in third person. */
    @Order(1)
    @Name("Own name tag")
    @Key("show-to-self")
    @NoExplanationNeeded
    default boolean showToSelf() {
        return true;
    }

    @Order(2)
    @Name("Update interval")
    @Key("update-interval")
    @Explain("Seconds between redraws.")
    default int updateInterval() {
        return 1;
    }

    @Order(3)
    @Name("Visibility distance")
    @Key("visibility-distance")
    @Explain("In blocks.")
    default int visibilityDistance() {
        return 32;
    }

    @Order(4)
    @Name("Text shadow")
    @Key("text-shadow")
    @NoExplanationNeeded
    default boolean textShadow() {
        return true;
    }

    @Order(5)
    @Name("Through blocks")
    @Key("see-through")
    @Explain("vanilla dims it like Minecraft's own, true is full brightness, false hides it.")
    @AllowedValues({"vanilla", "true", "false"})
    default String seeThrough() {
        return SeeThroughMode.VANILLA.configValue();
    }

    @Order(6)
    @Name("Opacity while sneaking")
    @Key("sneak-text-opacity")
    @Explain("0 to 255, vanilla is 32; -1 keeps the text opaque. The client draws nothing for 4 to 26.")
    default int sneakTextOpacity() {
        return 32;
    }

    @Order(7)
    @Name("Text alignment")
    @Key("text-alignment")
    @NoExplanationNeeded
    @AllowedValues({"left", "right", "center"})
    default String textAlignment() {
        return TextAlignment.CENTER.name().toLowerCase(Locale.ROOT);
    }

    @Order(8)
    @Name("Background")
    @Key("background")
    @Explain("default, transparent or a hex colour such as #FFFFFF.")
    default String background() {
        return "default";
    }

    /** Which axes the text turns along to face the viewer. */
    @Order(9)
    @Name("Billboard")
    @Key("billboard")
    @NoExplanationNeeded
    @AllowedValues({"fixed", "vertical", "horizontal", "center"})
    default String billboard() {
        return Display.Billboard.CENTER.name().toLowerCase(Locale.ROOT);
    }

    @Order(10)
    @Name("Offset")
    @Key("offset")
    @Explain("In blocks; y 0.25 is just above the head.")
    default VectorSpec offset() {
        return Specs.createUnsafe(VectorSpec.class, Map.of("x", 0.0, "y", 0.25, "z", 0.0));
    }

    @Order(11)
    @Name("Scale")
    @Key("scale")
    @NoExplanationNeeded
    default VectorSpec scale() {
        return Specs.createUnsafe(VectorSpec.class, Map.of("x", 1.0, "y", 1.0, "z", 1.0));
    }
}
