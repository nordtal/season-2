package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;

/**
 * The {@code prestige} group: the thirteen crest tiers, when each is reached and the colour a name is drawn in.
 *
 * Hours and colours are sibling blocks with the same keys, so steward pairs them; {@code admin} sits beside both.
 */
@ConfigSpec
public interface PrestigeSpec {

    @Order(1)
    @Name("Admin")
    @Key("admin")
    @Comment("Wins over every prestige tier. Keep it far from every tier's colour, as vanilla RED is.")
    @Explain("Overrides every prestige tier below, rather than being a fourteenth tier of its own.")
    default String admin() {
        return "#ff5555";
    }

    @Order(2)
    @Name("Hours per tier")
    @Key("hours")
    @Comment({
        "When each tier is reached, in hours of network-wide online time. Exactly thirteen,",
        "the first 0, rising strictly."
    })
    @NoExplanationNeeded
    default TierHoursSpec hours() {
        return Specs.createDefault(TierHoursSpec.class);
    }

    @Order(3)
    @Name("Tier colours")
    @Key("colours")
    @Comment("The thirteen tiers' name colours, low to high. Tier 1 is everybody's from their first second.")
    @NoExplanationNeeded
    default TierColoursSpec colours() {
        return Specs.createDefault(TierColoursSpec.class);
    }

    /** The hour each {@link eu.nordtal.s2.smp.prestige.Prestige} tier is reached at, in order. */
    @ConfigSpec
    interface TierHoursSpec {

        @Order(1)
        @Name("Tier 1")
        @Key("tier-01")
        @Comment("Tier 1 is everybody's from their first second, so this one is 0 and stays 0.")
        @NoExplanationNeeded
        default int tier01() {
            return 0;
        }

        @Order(2)
        @Name("Tier 2")
        @Key("tier-02")
        @Comment("An evening.")
        @NoExplanationNeeded
        default int tier02() {
            return 2;
        }

        @Order(3)
        @Name("Tier 3")
        @Key("tier-03")
        @Comment("A first weekend.")
        @NoExplanationNeeded
        default int tier03() {
            return 5;
        }

        @Order(4)
        @Name("Tier 4")
        @Key("tier-04")
        @Comment("A week of evenings.")
        @NoExplanationNeeded
        default int tier04() {
            return 10;
        }

        @Order(5)
        @Name("Tier 5")
        @Key("tier-05")
        @Comment("Two weeks.")
        @NoExplanationNeeded
        default int tier05() {
            return 20;
        }

        @Order(6)
        @Name("Tier 6")
        @Key("tier-06")
        @Comment("A month of evenings.")
        @NoExplanationNeeded
        default int tier06() {
            return 35;
        }

        @Order(7)
        @Name("Tier 7")
        @Key("tier-07")
        @Comment("Six weeks.")
        @NoExplanationNeeded
        default int tier07() {
            return 55;
        }

        @Order(8)
        @Name("Tier 8")
        @Key("tier-08")
        @Comment("Two months.")
        @NoExplanationNeeded
        default int tier08() {
            return 85;
        }

        @Order(9)
        @Name("Tier 9")
        @Key("tier-09")
        @Comment("Ten weeks.")
        @NoExplanationNeeded
        default int tier09() {
            return 125;
        }

        @Order(10)
        @Name("Tier 10")
        @Key("tier-10")
        @Comment("Three months of regular play.")
        @NoExplanationNeeded
        default int tier10() {
            return 175;
        }

        @Order(11)
        @Name("Tier 11")
        @Key("tier-11")
        @Comment("The long middle of the season.")
        @NoExplanationNeeded
        default int tier11() {
            return 250;
        }

        @Order(12)
        @Name("Tier 12")
        @Key("tier-12")
        @Comment("The second-to-last crest; reachable, not guaranteed.")
        @NoExplanationNeeded
        default int tier12() {
            return 350;
        }

        @Order(13)
        @Name("Tier 13")
        @Key("tier-13")
        @Comment(
                "Legend. Two to three months for somebody who plays regularly and leaves the client running some nights.")
        @NoExplanationNeeded
        default int tier13() {
            return 500;
        }
    }

    /** One colour per {@link eu.nordtal.s2.smp.prestige.Prestige} tier, in order. */
    @ConfigSpec
    interface TierColoursSpec {

        @Order(1)
        @Name("Tier 1")
        @Key("tier-01")
        @Comment("Teal, the colour of a crest nobody has worn for long.")
        @NoExplanationNeeded
        default String tier01() {
            return "#5fbfae";
        }

        @Order(2)
        @Name("Tier 2")
        @Key("tier-02")
        @Comment("Sky blue.")
        @NoExplanationNeeded
        default String tier02() {
            return "#5ea9d6";
        }

        @Order(3)
        @Name("Tier 3")
        @Key("tier-03")
        @Comment("Cornflower.")
        @NoExplanationNeeded
        default String tier03() {
            return "#6f93e0";
        }

        @Order(4)
        @Name("Tier 4")
        @Key("tier-04")
        @Comment("Periwinkle.")
        @NoExplanationNeeded
        default String tier04() {
            return "#8f83e6";
        }

        @Order(5)
        @Name("Tier 5")
        @Key("tier-05")
        @Comment("Violet.")
        @NoExplanationNeeded
        default String tier05() {
            return "#a878e0";
        }

        @Order(6)
        @Name("Tier 6")
        @Key("tier-06")
        @Comment("Orchid.")
        @NoExplanationNeeded
        default String tier06() {
            return "#c96fd6";
        }

        @Order(7)
        @Name("Tier 7")
        @Key("tier-07")
        @Comment("Rose.")
        @NoExplanationNeeded
        default String tier07() {
            return "#dd6fae";
        }

        @Order(8)
        @Name("Tier 8")
        @Key("tier-08")
        @Comment("Coral.")
        @NoExplanationNeeded
        default String tier08() {
            return "#e07d78";
        }

        @Order(9)
        @Name("Tier 9")
        @Key("tier-09")
        @Comment("Orange.")
        @NoExplanationNeeded
        default String tier09() {
            return "#e2984f";
        }

        @Order(10)
        @Name("Tier 10")
        @Key("tier-10")
        @Comment("Gold.")
        @NoExplanationNeeded
        default String tier10() {
            return "#dbb043";
        }

        @Order(11)
        @Name("Tier 11")
        @Key("tier-11")
        @Comment("Bright gold.")
        @NoExplanationNeeded
        default String tier11() {
            return "#e8d35a";
        }

        @Order(12)
        @Name("Tier 12")
        @Key("tier-12")
        @Comment("Radiant gold.")
        @NoExplanationNeeded
        default String tier12() {
            return "#f0dc70";
        }

        @Order(13)
        @Name("Tier 13")
        @Key("tier-13")
        @Comment("Legend, the brightest and warmest colour of all fourteen, admin included.")
        @NoExplanationNeeded
        default String tier13() {
            return "#fff6d8";
        }
    }
}
