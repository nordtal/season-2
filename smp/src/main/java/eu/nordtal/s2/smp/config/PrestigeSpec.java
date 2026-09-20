package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * {@code prestige.yml} - the thirteen crest tiers: when each one is reached, and what a name at
 * that tier is drawn in.
 *
 * <h2>One file, because a tier is one thing (steward/130)</h2>
 * This was two lists in two files until 2026-09-20: the hours in {@code config.yml} as
 * {@code prestige-threshold-hours}, the colours here as {@code prestige-colours.yml}. Till, on
 * seeing them in steward: <em>the settings should be brought together; one place to set hours and
 * colours for a tier.</em> The defect that closes is not the walking between two pages - it is
 * that <b>two lists which belong together by position are one list with an unwritten contract</b>.
 * Whoever edited the seventh line of one had to find the seventh line of the other and trust that
 * the orders agreed; nothing checked that they did, and the failure - tier 7 turning gold at tier
 * 8's hours - is invisible in both files.
 *
 * <p>So {@link #hours()} and {@link #colours()} are two sibling blocks with the <b>same thirteen
 * keys</b>, in the same order, in one file. Steward draws them as one row per tier, and the
 * matching key sets are what lets it (see {@code pairedBlocks} in the frontend).</p>
 *
 * <p>A file of its own rather than a block in {@code config.yml}, for the same reason
 * {@code colours.yml} is: {@code /smp reload} re-reads it, and a season that is retuning its
 * crest ladder should not need a restart to see the change. Both halves are live-reloaded since
 * this ticket - the hours were not, because they lived in {@code config.yml}.</p>
 *
 * <p>Every colour is a hex string, for the same reason {@link ColoursSpec}'s are: a configured
 * colour has to be something a colour picker can hand back (steward/63), and
 * {@link eu.nordtal.s2.smp.prestige.PrestigeColours#parse} is where a bad one is caught - it
 * reports the problem and falls back to the default rather than stopping the server. The hours are
 * stricter, and they have to be: {@link eu.nordtal.s2.smp.prestige.Prestige}'s constructor refuses
 * a ladder that is not thirteen values rising strictly from zero, because a crest ladder that
 * skips is not a smaller mistake than a missing one.
 *
 * <p>{@link #admin()} sits beside both blocks rather than inside either: it is not a fourteenth
 * prestige tier, it is the one colour that overrides all thirteen (season-2-ingame/23). Keeping it
 * a sibling is what keeps steward's colour picker from ever offering it as part of a tier row, and
 * what keeps the two blocks' key sets identical.
 */
@ConfigSpec(header = {
        "smp - the prestige crest ladder",
        "",
        "Thirteen tiers, each with the online time that reaches it and the colour a name is drawn",
        "in once it does: chat, the tab list, the nametag above their head and every system line",
        "that names them (join, leave, death, advancement).",
        "",
        "THE TWO BLOCKS BELOW HAVE THE SAME THIRTEEN KEYS, ON PURPOSE. 'hours' and 'colours' are",
        "one ladder written twice, and steward shows them as one row per tier. Do not add a key to",
        "one without adding it to the other.",
        "",
        "HOURS are network-wide online time, AFK included on purpose: this is a measure of",
        "presence, not of effort, and it is the reason play time is not an aura source. The list",
        "must be exactly thirteen values, the first 0, rising strictly - thirteen because that is",
        "how many crest designs the resource pack draws, and a fourteenth tier would have nothing",
        "to render as. The tier is DERIVED and never stored, so retuning this is an edit and a",
        "'/smp reload' rather than a migration plus a backfill.",
        "",
        "EVERY COLOUR IS A HEX COLOUR, like #5fbfae. A value that is not a parseable hex colour is",
        "reported in the console and the default takes its place - it never stops the server,",
        "because a typo there is not worth the season going offline. A bad HOUR does stop the",
        "load, because a ladder that does not rise is not a colour that looks odd.",
        "",
        "'admin' is not a fourteenth prestige tier. It is the one colour that wins over all",
        "thirteen: an admin at tier 13 still shows this colour, never tier 13's, because authority",
        "is a role that can be revoked in an instant and a prestige tier is earned over a season.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_SMP_PRESTIGE_<PATH>, with '.' and '-' both becoming '_'."
})
public interface PrestigeSpec {

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

    @Order(2) @Key("hours")
    @Comment({
            "When each tier is reached, in hours of network-wide online time. Exactly thirteen,",
            "the first 0, rising strictly. Calibrated so tier 13 is reachable in two to three",
            "months by somebody who plays regularly and leaves the client running some nights."
    })
    @NoExplanationNeeded
    default TierHoursSpec hours() {
        // createDefault fills the instance from TierHoursSpec's own default bodies, so the thirteen
        // numbers exist exactly once - the same reason NetworkSpec.MotdSpec is built this way.
        return Specs.createDefault(TierHoursSpec.class);
    }

    @Order(3) @Key("colours")
    @Comment("The thirteen tiers' name colours, low to high. Tier 1 is everybody's from their first second.")
    @NoExplanationNeeded
    default TierColoursSpec colours() {
        return Specs.createDefault(TierColoursSpec.class);
    }

    /** The hour each {@link eu.nordtal.s2.smp.prestige.Prestige} tier is reached at, in order. */
    @ConfigSpec
    interface TierHoursSpec {

        @Order(1) @Key("tier-01")
        @Comment("Tier 1 is everybody's from their first second, so this one is 0 and stays 0.")
        @NoExplanationNeeded
        default int tier01() { return 0; }

        @Order(2) @Key("tier-02")
        @Comment("An evening.")
        @NoExplanationNeeded
        default int tier02() { return 2; }

        @Order(3) @Key("tier-03")
        @Comment("A first weekend.")
        @NoExplanationNeeded
        default int tier03() { return 5; }

        @Order(4) @Key("tier-04")
        @Comment("A week of evenings.")
        @NoExplanationNeeded
        default int tier04() { return 10; }

        @Order(5) @Key("tier-05")
        @Comment("Two weeks.")
        @NoExplanationNeeded
        default int tier05() { return 20; }

        @Order(6) @Key("tier-06")
        @Comment("A month of evenings.")
        @NoExplanationNeeded
        default int tier06() { return 35; }

        @Order(7) @Key("tier-07")
        @Comment("Six weeks.")
        @NoExplanationNeeded
        default int tier07() { return 55; }

        @Order(8) @Key("tier-08")
        @Comment("Two months.")
        @NoExplanationNeeded
        default int tier08() { return 85; }

        @Order(9) @Key("tier-09")
        @Comment("Ten weeks.")
        @NoExplanationNeeded
        default int tier09() { return 125; }

        @Order(10) @Key("tier-10")
        @Comment("Three months of regular play.")
        @NoExplanationNeeded
        default int tier10() { return 175; }

        @Order(11) @Key("tier-11")
        @Comment("The long middle of the season.")
        @NoExplanationNeeded
        default int tier11() { return 250; }

        @Order(12) @Key("tier-12")
        @Comment("The second-to-last crest; reachable, not guaranteed.")
        @NoExplanationNeeded
        default int tier12() { return 350; }

        @Order(13) @Key("tier-13")
        @Comment("Legend. Two to three months for somebody who plays regularly and leaves the client running some nights.")
        @NoExplanationNeeded
        default int tier13() { return 500; }
    }

    /** One colour per {@link eu.nordtal.s2.smp.prestige.Prestige} tier, in order. */
    @ConfigSpec
    interface TierColoursSpec {

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
