package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code colours.yml} - the five {@link eu.nordtal.s2.common.message.Tone} colours a reply is
 * painted with (season-2-ingame/22).
 *
 * <p>Every value below is deliberately the same as {@code smp}'s, so the network reads as one server
 * rather than three. Loaded once at enable: {@code /limbo reload} touches only the message bundles -
 * {@code LimboEffects} names exactly that one thing, and widening it is a change to a shared
 * interface in {@code :commands}, out of this ticket's file scope - so a change here needs a
 * restart, the same as {@code config.yml}.
 */
@ConfigSpec(
        header = {
            "limbo - tone colours",
            "",
            "The five colours a reply can be painted with. Every reply through NordtalUser#reply names",
            "one of GOOD, BAD, WARN, MUTED or NEUTRAL, and this file is what each of those five looks",
            "like on this server. Identical to smp's own colours.yml on purpose.",
            "",
            "EVERY VALUE IS A HEX COLOUR, like #8ba888. A value that is not a parseable hex colour is",
            "reported in the console and the default takes its place - it never stops the server.",
            "",
            "READ ONCE AT ENABLE. /limbo reload only re-reads the message bundles, so a change here",
            "needs a restart.",
            "",
            "Every setting can be overridden with an environment variable named",
            "NORDTAL_LIMBO_COLOURS_<PATH>, with '.' and '-' both becoming '_'."
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
    @Comment("Not a failure, but not what was asked for either - stopped, too late, still waiting.")
    @Explain("Not a failure, but not what was asked for either - stopped, too late, still waiting.")
    default String warn() {
        return "#b08a4a";
    }

    @Order(4)
    @Name("Neutral")
    @Key("neutral")
    @Comment("An ordinary reply - nothing to flag. Lighter than muted, so the two are distinguishable.")
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
