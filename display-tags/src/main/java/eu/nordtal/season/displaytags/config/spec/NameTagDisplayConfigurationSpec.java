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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.Display;

/** How a name tag's text display looks. */
@ConfigSpec
public interface NameTagDisplayConfigurationSpec {

    @Order(1)
    @Name("Lines")
    @Key("lines")
    @Explain("Top to bottom, in MiniMessage; {player} is the name and {health} the health.")
    default List<String> lines() {
        return List.of("<gray>{player}</gray>", "<red>❤ <white>{health}");
    }

    @Order(2)
    @Name("Text shadow")
    @Key("text-shadow")
    @NoExplanationNeeded
    default boolean textShadow() {
        return true;
    }

    @Order(3)
    @Name("Through blocks")
    @Key("see-through")
    @Explain("vanilla draws it dimmed through blocks like Minecraft's own, true at full brightness, false not.")
    @AllowedValues({"vanilla", "true", "false"})
    default String seeThrough() {
        return SeeThroughMode.VANILLA.configValue();
    }

    @Order(4)
    @Name("Opacity while sneaking")
    @Key("sneak-text-opacity")
    @Explain("0 to 255, vanilla is 32; -1 keeps the text opaque. The client draws nothing for 4 to 26.")
    default int sneakTextOpacity() {
        return 32;
    }

    @Order(5)
    @Name("Text alignment")
    @Key("text-alignment")
    @NoExplanationNeeded
    @AllowedValues({"left", "right", "center"})
    default String textAlignment() {
        return TextAlignment.CENTER.name().toLowerCase(Locale.ROOT);
    }

    @Order(6)
    @Name("Background")
    @Key("background")
    @Explain("default, transparent or a hex colour such as #FFFFFF.")
    default String background() {
        return "default";
    }

    @Order(7)
    @Name("Billboard")
    @Key("billboard")
    @Explain("Which axes the text turns along to face the viewer.")
    @AllowedValues({"fixed", "vertical", "horizontal", "center"})
    default String billboard() {
        return Display.Billboard.CENTER.name().toLowerCase(Locale.ROOT);
    }

    @Order(8)
    @Name("Offset")
    @Key("offset")
    @Explain("Where the text sits relative to the player, in blocks; y 0.25 is just above the head.")
    default VectorSpec offset() {
        return Specs.createUnsafe(VectorSpec.class, Map.of("x", 0.0, "y", 0.25, "z", 0.0));
    }

    @Order(9)
    @Name("Scale")
    @Key("scale")
    @NoExplanationNeeded
    default VectorSpec scale() {
        return Specs.createUnsafe(VectorSpec.class, Map.of("x", 1.0, "y", 1.0, "z", 1.0));
    }
}
