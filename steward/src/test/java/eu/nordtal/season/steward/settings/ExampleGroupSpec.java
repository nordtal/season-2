package eu.nordtal.season.steward.settings;

import eu.nordtal.season.settings.AppliesWhen;
import eu.nordtal.season.settings.ChoiceNames;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Secret;
import java.util.List;

/** A group with a number, a text, lists, sections and a secret, in an order no sorting of their keys gives. */
@ConfigSpec
public interface ExampleGroupSpec {

    @Order(1)
    @Key("max-players")
    default int maxPlayers() {
        return 20;
    }

    @Order(2)
    @Key("motd")
    default String motd() {
        return "Nordtal";
    }

    @Order(3)
    @Key("allowlist")
    default List<String> allowlist() {
        return List.of("msg");
    }

    @Order(4)
    @Key("prizes")
    @Refers(Refers.To.ITEM)
    default List<String> prizes() {
        return List.of("minecraft:diamond");
    }

    @Order(5)
    @Key("stages")
    default List<Stage> stages() {
        return List.of();
    }

    @Order(6)
    @Key("token")
    @Secret
    default String token() {
        return "";
    }

    /** One stage, whose statistic comes before the subjects it counts, and which counts only when its kind says so. */
    @ConfigSpec
    interface Stage {

        @Order(1)
        @Key("statistic")
        @Refers(value = Refers.To.STATISTIC, except = "minecraft:play_one_minute")
        @AppliesWhen(key = "kind", values = "COUNTED")
        default String statistic() {
            return "";
        }

        @Order(2)
        @Key("subjects")
        @Refers(value = Refers.To.SUBJECT, dependsOn = "statistic")
        default List<String> subjects() {
            return List.of();
        }

        @Order(3)
        @Key("key")
        default String key() {
            return "";
        }

        @Order(4)
        @Key("kind")
        @AllowedValues({"COUNTED", "GIVEN"})
        @ChoiceNames(
                value = "example.kind",
                icons = {"minecraft:clock", "minecraft:chest"})
        default String kind() {
            return "COUNTED";
        }
    }
}
