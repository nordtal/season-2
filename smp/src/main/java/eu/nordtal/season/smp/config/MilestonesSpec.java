package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.AppliesWhen;
import eu.nordtal.season.settings.ChoiceNames;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/**
 * The {@code milestones} group: the track, reloadable, while the progress lives in the database.
 *
 * One record shape serves all three objective types, and each objective carries the aura and spins it pays out.
 */
@ConfigSpec
public interface MilestonesSpec {

    /** The file's order is the season's order. */
    @Order(1)
    @Name("Milestones")
    @Key("milestones")
    @Explain("In season order. Renaming one with progress is refused.")
    default List<MilestoneEntry> milestones() {
        return DefaultTrack.LIST;
    }

    /** One milestone of the track. */
    @ConfigSpec
    interface MilestoneEntry {

        /** The primary key in smp_milestone. */
        @Order(1)
        @Name("ID")
        @Key("key")
        @Explain("Renaming one with progress is refused.")
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
        @Explain("In blocks.")
        @AppliesWhen(key = "unlocks", values = "BORDER")
        default int borderDiameter() {
            return 0;
        }

        /** Opened by an admin rather than by objectives. */
        @Order(4)
        @Name("Unlocked by admin")
        @Key("admin-unlocked")
        @Explain("True only for the opening milestone.")
        default boolean adminUnlocked() {
            return false;
        }

        @Order(5)
        @Name("Objectives")
        @Key("objectives")
        @Explain("All must finish. Exactly one is an ADVANCEMENT, the gate.")
        default List<ObjectiveEntry> objectives() {
            return List.of();
        }
    }

    /** One objective; which of the fields below apply depends on {@code type}. */
    @ConfigSpec
    interface ObjectiveEntry {

        @Order(1)
        @Name("ID")
        @Key("key")
        @Explain("Unique within its milestone. Never rename one with progress.")
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

        /** What this objective is for, such as gathering; never read by the engine, it keeps categories apart. */
        @Order(3)
        @Name("Role")
        @Key("role")
        @NoExplanationNeeded
        default String role() {
            return "";
        }

        @Order(4)
        @Name("Target")
        @Key("target")
        @Explain(
                "Lowering this on a live objective is always allowed; changing it on one that already completed is refused.")
        default long target() {
            return 1L;
        }

        /** Times reached over target when an admin closes it. */
        @Order(5)
        @Name("Aura budget")
        @Key("aura-budget")
        @Explain("Paid out exactly among its contributors on completion.")
        default int auraBudget() {
            return 0;
        }

        /** The extra wheel spins it pays out, the same way as its aura. */
        @Order(6)
        @Name("Spin budget")
        @Key("spin-budget")
        @NoExplanationNeeded
        default int spinBudget() {
            return 0;
        }

        @Order(7)
        @Name("Items")
        @Key("items")
        @Explain("Any one counts. An unknown item stops the plugin.")
        @Refers(Refers.To.ITEM)
        @AppliesWhen(key = "type", values = "HAND_IN")
        default List<String> items() {
            return List.of();
        }

        /** Summed across players; one that grows by time online is not offered. */
        @Order(8)
        @Name("Statistic")
        @Key("statistic")
        @NoExplanationNeeded
        // A statistic that grows while a player only stays online would pay everybody for being there.
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

        /** The materials or entity types the statistic is summed over. */
        @Order(9)
        @Name("Subjects")
        @Key("subjects")
        @Explain("May be empty.")
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
