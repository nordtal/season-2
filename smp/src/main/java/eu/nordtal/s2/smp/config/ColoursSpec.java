package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code colours.yml}: the five {@link eu.nordtal.s2.common.message.Tone} colours a reply is painted with, reloadable.
 *
 * Every value is a hex string; an invalid one is reported and the default takes over.
 */
@ConfigSpec(
        header = {
            "smp: tone colours",
            "",
            "The five colours a reply can be painted with: GOOD, BAD, WARN, MUTED and NEUTRAL.",
            "",
            "EVERY VALUE IS A HEX COLOUR, like #8ba888. An invalid one is reported in the console",
            "and the default takes its place; it never stops the server.",
            "",
            "Every setting can be overridden with an environment variable named",
            "NORDTAL_SMP_COLOURS_<PATH>, with '.' and '-' both becoming '_'."
        })
public interface ColoursSpec {

    @Order(1)
    @Name("Good")
    @Key("good")
    @Comment("Arriving: it worked, it is current, it came back.")
    @Explain("For a reply that arrives: it worked, is current, or came back.")
    default String good() {
        return "#8ba888";
    }

    @Order(2)
    @Name("Bad")
    @Key("bad")
    @Comment("Leaving: it failed. The one tone that has to be findable in a list of forty lines.")
    @Explain("For a reply that fails. Needs to stand out in a long list.")
    default String bad() {
        return "#a8888b";
    }

    @Order(3)
    @Name("Warning")
    @Key("warn")
    @Comment("Not a failure, but not what was asked for either: stopped, too late, still waiting.")
    @Explain("Not a failure, but not what was asked for either: stopped, too late, still waiting.")
    default String warn() {
        return "#b08a4a";
    }

    @Order(4)
    @Name("Neutral")
    @Key("neutral")
    @Comment("An ordinary reply with nothing to flag. Lighter than muted, so the two stay distinguishable.")
    @Explain("An ordinary reply with nothing to flag.")
    default String neutral() {
        return "#c9c9c9";
    }

    @Order(5)
    @Name("Muted")
    @Key("muted")
    @Comment("Supporting detail under a line that already carries the news.")
    @Explain("Supporting detail under a line that already carries the news.")
    default String muted() {
        return "#aaaaaa";
    }
}
