package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.AppliesWhen;
import eu.nordtal.season.settings.ChoiceNames;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code milestones} group. It holds the track, and the database holds the progress. */
@ConfigSpec
public interface MilestonesSpec {

    @Order(1)
    @Name("Milestones")
    @Key("milestones")
    @NoExplanationNeeded
    default List<MilestoneEntry> milestones() {
        return DefaultTrack.LIST;
    }

    @ConfigSpec
    interface MilestoneEntry {

        /** The key is the primary key in {@code smp_milestone}. */
        @Order(1)
        @Name("ID")
        @Key("key")
        @NoExplanationNeeded
        default String key() {
            return "";
        }

        @Order(2)
        @Name("Unlocks")
        @Key("unlocks")
        @NoExplanationNeeded
        @AllowedValues({"BORDER", "NETHER", "END", "NOTHING"})
        @ChoiceNames(
                value = "smp.settings.unlock",
                icons = {"minecraft:map", "minecraft:netherrack", "minecraft:ender_eye", "minecraft:barrier"})
        default String unlocks() {
            return "NOTHING";
        }

        @Order(3)
        @Name("Border diameter")
        @Key("border-diameter")
        @NoExplanationNeeded
        @AppliesWhen(key = "unlocks", values = "BORDER")
        default int borderDiameter() {
            return 0;
        }

        @Order(4)
        @Name("Unlocked by admin")
        @Key("admin-unlocked")
        @NoExplanationNeeded
        default boolean adminUnlocked() {
            return false;
        }

        /** A milestone finishes when all its objectives finish. Exactly one is an ADVANCEMENT objective, the gate. */
        @Order(5)
        @Name("Objectives")
        @Key("objectives")
        @NoExplanationNeeded
        default List<ObjectiveEntry> objectives() {
            return List.of();
        }
    }

    @ConfigSpec
    interface ObjectiveEntry {

        /** The key is unique within its milestone. A key with progress must not be renamed. */
        @Order(1)
        @Name("ID")
        @Key("key")
        @NoExplanationNeeded
        default String key() {
            return "";
        }

        @Order(2)
        @Name("Type")
        @Key("type")
        @NoExplanationNeeded
        @AllowedValues({"HAND_IN", "STATISTIC", "ADVANCEMENT"})
        @ChoiceNames(
                value = "smp.settings.objective-type",
                icons = {"minecraft:chest", "minecraft:writable_book", "minecraft:knowledge_book"})
        default String type() {
            return "HAND_IN";
        }

        /** The engine does not read {@code role}. */
        @Order(3)
        @Name("Role")
        @Key("role")
        @NoExplanationNeeded
        default String role() {
            return "";
        }

        /** The target of a live objective may be lowered. The target of a completed objective is refused. */
        @Order(4)
        @Name("Target")
        @Key("target")
        @NoExplanationNeeded
        default long target() {
            return 1L;
        }

        @Order(5)
        @Name("Aura budget")
        @Key("aura-budget")
        @NoExplanationNeeded
        default int auraBudget() {
            return 0;
        }

        @Order(6)
        @Name("Spin budget")
        @Key("spin-budget")
        @NoExplanationNeeded
        default int spinBudget() {
            return 0;
        }

        /** An unknown item stops the plugin. */
        @Order(7)
        @Name("Items")
        @Key("items")
        @NoExplanationNeeded
        @Refers(Refers.To.ITEM)
        @AppliesWhen(key = "type", values = "HAND_IN")
        default List<String> items() {
            return List.of();
        }

        @Order(8)
        @Name("Statistic")
        @Key("statistic")
        @NoExplanationNeeded
        @Refers(
                value = Refers.To.STATISTIC,
                except = {
                    "minecraft:play_one_minute",
                    "minecraft:total_world_time",
                    "minecraft:time_since_death",
                    "minecraft:time_since_rest"
                })
        @AppliesWhen(key = "type", values = "STATISTIC")
        default String statistic() {
            return "";
        }

        @Order(9)
        @Name("Subjects")
        @Key("subjects")
        @NoExplanationNeeded
        @Refers(value = Refers.To.SUBJECT, dependsOn = "statistic")
        @AppliesWhen(key = "type", values = "STATISTIC")
        default List<String> subjects() {
            return List.of();
        }

        @Order(10)
        @Name("Advancement")
        @Key("advancement")
        @NoExplanationNeeded
        @Refers(Refers.To.ADVANCEMENT)
        @AppliesWhen(key = "type", values = "ADVANCEMENT")
        default String advancement() {
            return "";
        }
    }
}
