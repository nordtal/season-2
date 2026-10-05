package eu.nordtal.season.settings.network;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/**
 * The network's {@code prestige} group: when each of the thirteen crests is reached, and the name colours.
 * Every name's card shows the crest, and smp paints names in the colours.
 * Hours and colours are sibling blocks with the same keys, so steward pairs them; {@code admin} sits beside both.
 */
@ConfigSpec
public interface PrestigeSpec {

    @Order(1)
    @Name("Admin")
    @Key("admin")
    @Explain("Overrides every prestige tier below, rather than being a fourteenth tier of its own.")
    @Refers(Refers.To.COLOUR)
    default String admin() {
        return "#ff5555";
    }

    @Order(2)
    @Name("Hours per tier")
    @Key("hours")
    @NoExplanationNeeded
    default TierHoursSpec hours() {
        return Specs.createDefault(TierHoursSpec.class);
    }

    @Order(3)
    @Name("Tier colours")
    @Key("colours")
    @NoExplanationNeeded
    default TierColoursSpec colours() {
        return Specs.createDefault(TierColoursSpec.class);
    }

    /** The hour each {@link eu.nordtal.season.database.access.Prestige} tier is reached at, in order. */
    @ConfigSpec
    interface TierHoursSpec {

        @Order(1)
        @Name("Tier 1")
        @Key("tier-01")
        @NoExplanationNeeded
        default int tier01() {
            return 0;
        }

        @Order(2)
        @Name("Tier 2")
        @Key("tier-02")
        @NoExplanationNeeded
        default int tier02() {
            return 2;
        }

        @Order(3)
        @Name("Tier 3")
        @Key("tier-03")
        @NoExplanationNeeded
        default int tier03() {
            return 5;
        }

        @Order(4)
        @Name("Tier 4")
        @Key("tier-04")
        @NoExplanationNeeded
        default int tier04() {
            return 10;
        }

        @Order(5)
        @Name("Tier 5")
        @Key("tier-05")
        @NoExplanationNeeded
        default int tier05() {
            return 20;
        }

        @Order(6)
        @Name("Tier 6")
        @Key("tier-06")
        @NoExplanationNeeded
        default int tier06() {
            return 35;
        }

        @Order(7)
        @Name("Tier 7")
        @Key("tier-07")
        @NoExplanationNeeded
        default int tier07() {
            return 55;
        }

        @Order(8)
        @Name("Tier 8")
        @Key("tier-08")
        @NoExplanationNeeded
        default int tier08() {
            return 85;
        }

        @Order(9)
        @Name("Tier 9")
        @Key("tier-09")
        @NoExplanationNeeded
        default int tier09() {
            return 125;
        }

        @Order(10)
        @Name("Tier 10")
        @Key("tier-10")
        @NoExplanationNeeded
        default int tier10() {
            return 175;
        }

        @Order(11)
        @Name("Tier 11")
        @Key("tier-11")
        @NoExplanationNeeded
        default int tier11() {
            return 250;
        }

        @Order(12)
        @Name("Tier 12")
        @Key("tier-12")
        @NoExplanationNeeded
        default int tier12() {
            return 350;
        }

        @Order(13)
        @Name("Tier 13")
        @Key("tier-13")
        @NoExplanationNeeded
        default int tier13() {
            return 500;
        }
    }

    /** One colour per {@link eu.nordtal.season.database.access.Prestige} tier, in order. */
    @ConfigSpec
    interface TierColoursSpec {

        @Order(1)
        @Name("Tier 1")
        @Key("tier-01")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier01() {
            return "#5fbfae";
        }

        @Order(2)
        @Name("Tier 2")
        @Key("tier-02")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier02() {
            return "#5ea9d6";
        }

        @Order(3)
        @Name("Tier 3")
        @Key("tier-03")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier03() {
            return "#6f93e0";
        }

        @Order(4)
        @Name("Tier 4")
        @Key("tier-04")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier04() {
            return "#8f83e6";
        }

        @Order(5)
        @Name("Tier 5")
        @Key("tier-05")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier05() {
            return "#a878e0";
        }

        @Order(6)
        @Name("Tier 6")
        @Key("tier-06")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier06() {
            return "#c96fd6";
        }

        @Order(7)
        @Name("Tier 7")
        @Key("tier-07")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier07() {
            return "#dd6fae";
        }

        @Order(8)
        @Name("Tier 8")
        @Key("tier-08")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier08() {
            return "#e07d78";
        }

        @Order(9)
        @Name("Tier 9")
        @Key("tier-09")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier09() {
            return "#e2984f";
        }

        @Order(10)
        @Name("Tier 10")
        @Key("tier-10")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier10() {
            return "#dbb043";
        }

        @Order(11)
        @Name("Tier 11")
        @Key("tier-11")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier11() {
            return "#e8d35a";
        }

        @Order(12)
        @Name("Tier 12")
        @Key("tier-12")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier12() {
            return "#f0dc70";
        }

        @Order(13)
        @Name("Tier 13")
        @Key("tier-13")
        @NoExplanationNeeded
        @Refers(Refers.To.COLOUR)
        default String tier13() {
            return "#fff6d8";
        }
    }
}
