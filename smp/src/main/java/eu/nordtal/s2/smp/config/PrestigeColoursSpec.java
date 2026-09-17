package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code prestige-colours.yml} - the colour a player's name is drawn in, everywhere it appears
 * (season-2-ingame/23): chat, the tab list, the nametag above their head and every system line that
 * names them.
 *
 * <p>A file of its own rather than a block in {@code colours.yml}, for the same reason
 * {@code colours.yml} is not a block in {@code config.yml}: {@code /smp reload} re-reads it, and it
 * is a different idea from the five {@code Tone} colours - those paint a reply, this paints a person.
 *
 * <p>Every value is a hex string, for the same reason {@link ColoursSpec}'s are: a configured colour
 * has to be something a colour picker can hand back (steward/63), and
 * {@link eu.nordtal.s2.smp.prestige.PrestigeColours#parse} is where a bad one is caught - it reports
 * the problem and falls back to the default rather than stopping the server.
 *
 * <p>{@link #admin()} sits beside {@link #prestige()} rather than inside it on purpose: it is not a
 * fourteenth prestige tier, it is the one colour that overrides all thirteen (season-2-ingame/23).
 * Keeping it a sibling key rather than a fourteenth entry in {@link TierSpec} is what keeps steward's
 * colour picker from ever offering it as part of that row.
 */
@ConfigSpec(header = {
        "smp - prestige name colours",
        "",
        "What a player's name is drawn in, everywhere it appears: chat, the tab list, the nametag",
        "above their head and every system line that names them (join, leave, death, advancement).",
        "",
        "EVERY VALUE IS A HEX COLOUR, like #5fbfae. A value that is not a parseable hex colour is",
        "reported in the console and the default takes its place - it never stops the server, because",
        "a typo here is not worth the season going offline.",
        "",
        "'admin' is not a fourteenth prestige tier. It is the one colour that wins over all thirteen:",
        "an admin at tier 13 still shows this colour, never tier 13's, because authority is a role",
        "that can be revoked in an instant and a prestige tier is earned over a season.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_SMP_PRESTIGE_COLOURS_<PATH>, with '.' and '-' both becoming '_'."
})
public interface PrestigeColoursSpec {

    @Order(1) @Key("admin")
    @Comment({
            "Wins over every prestige tier. Default is vanilla's own RED (#ff5555) - no prestige",
            "tier's default is anywhere near it, so an admin's name never reads as \"maybe a high",
            "tier\" by accident."
    })
    @Explain("Overrides every prestige tier below - not a fourteenth tier of its own.")
    default String admin() {
        return "#ff5555";
    }

    @Order(2) @Key("prestige")
    @Comment("The thirteen prestige tiers, low to high. Tier 1 is everybody's from their first second.")
    @NoExplanationNeeded
    default TierSpec prestige() {
        // createDefault fills the instance from TierSpec's own default bodies, so the thirteen hex
        // strings exist exactly once - the same reason NetworkSpec.MotdSpec is built this way.
        return Specs.createDefault(TierSpec.class);
    }

    /** One colour per {@link eu.nordtal.s2.smp.prestige.Prestige} tier, in order. */
    @ConfigSpec
    interface TierSpec {

        @Order(1) @Key("tier-01")
        @Comment("Teal - the colour of a crest nobody has worn for long.")
        @NoExplanationNeeded
        default String tier01() { return "#5fbfae"; }

        @Order(2) @Key("tier-02")
        @Comment("Sky blue.")
        @NoExplanationNeeded
        default String tier02() { return "#5ea9d6"; }

        @Order(3) @Key("tier-03")
        @Comment("Cornflower.")
        @NoExplanationNeeded
        default String tier03() { return "#6f93e0"; }

        @Order(4) @Key("tier-04")
        @Comment("Periwinkle.")
        @NoExplanationNeeded
        default String tier04() { return "#8f83e6"; }

        @Order(5) @Key("tier-05")
        @Comment("Violet.")
        @NoExplanationNeeded
        default String tier05() { return "#a878e0"; }

        @Order(6) @Key("tier-06")
        @Comment("Orchid.")
        @NoExplanationNeeded
        default String tier06() { return "#c96fd6"; }

        @Order(7) @Key("tier-07")
        @Comment("Rose.")
        @NoExplanationNeeded
        default String tier07() { return "#dd6fae"; }

        @Order(8) @Key("tier-08")
        @Comment("Coral.")
        @NoExplanationNeeded
        default String tier08() { return "#e07d78"; }

        @Order(9) @Key("tier-09")
        @Comment("Orange.")
        @NoExplanationNeeded
        default String tier09() { return "#e2984f"; }

        @Order(10) @Key("tier-10")
        @Comment("Gold.")
        @NoExplanationNeeded
        default String tier10() { return "#dbb043"; }

        @Order(11) @Key("tier-11")
        @Comment("Bright gold.")
        @NoExplanationNeeded
        default String tier11() { return "#e8d35a"; }

        @Order(12) @Key("tier-12")
        @Comment("Radiant gold.")
        @NoExplanationNeeded
        default String tier12() { return "#f0dc70"; }

        @Order(13) @Key("tier-13")
        @Comment("Legend - the brightest, warmest colour of all fourteen (thirteen tiers plus admin).")
        @NoExplanationNeeded
        default String tier13() { return "#fff6d8"; }
    }
}
