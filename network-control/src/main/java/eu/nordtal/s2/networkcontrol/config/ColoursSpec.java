package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code colours.yml} - the five {@link eu.nordtal.s2.common.message.Tone} colours a reply is
 * painted with (season-2-ingame/22).
 *
 * <p>Every value below is deliberately the same as {@code smp}'s and {@code limbo}'s, so the
 * network reads as one server rather than several. READ AT PROXY START, the same as
 * {@code network.yml} and {@code gate.yml} - there is no reload command here, and widening
 * {@code /network reload} to touch it is a change to the shared {@code NetworkEffects} interface
 * in {@code :commands}, out of this ticket's file scope. A change here needs a proxy restart.
 */
@ConfigSpec(header = {
        "network-control - tone colours",
        "",
        "The five colours a reply can be painted with. Every reply through NordtalUser#reply names",
        "one of GOOD, BAD, WARN, MUTED or NEUTRAL, and this file is what each of those five looks",
        "like on this proxy. Identical to smp's and limbo's own colours.yml on purpose.",
        "",
        "EVERY VALUE IS A HEX COLOUR, like #8ba888. A value that is not a parseable hex colour is",
        "reported in the console and the default takes its place - it never stops the proxy.",
        "",
        "READ ONCE AT PROXY START. There is no reload command for this file, the same as network.yml",
        "and gate.yml.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_NETWORK_CONTROL_COLOURS_<PATH>, with '.' and '-' both becoming '_'."
})
public interface ColoursSpec {

    @Order(1) @Key("good")
    @Comment("Arriving: it worked, it is current, it came back.")
    default String good() { return "#8ba888"; }

    @Order(2) @Key("bad")
    @Comment("Leaving: it failed. The one tone that has to be findable in a list of forty lines.")
    default String bad() { return "#a8888b"; }

    @Order(3) @Key("warn")
    @Comment("Not a failure, but not what was asked for either - stopped, too late, still waiting.")
    default String warn() { return "#b08a4a"; }

    @Order(4) @Key("neutral")
    @Comment("An ordinary reply - nothing to flag. Lighter than muted, so the two are distinguishable.")
    default String neutral() { return "#c9c9c9"; }

    @Order(5) @Key("muted")
    @Comment("Supporting detail under a line that already carries the news.")
    default String muted() { return "#aaaaaa"; }
}
