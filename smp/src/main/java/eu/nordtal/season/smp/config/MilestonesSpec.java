package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.AllowedValues;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/**
 * The {@code milestones} group: the track, reloadable, while the progress lives in the database.
 *
 * One record shape serves all three objective types, and the pot is per milestone so its derivation cannot drift.
 */
@ConfigSpec
public interface MilestonesSpec {

    @Order(1)
    @Name("Milestones")
    @Key("milestones")
    @Explain("The track, in order: the file's order IS the season's order. Renaming one with progress is refused.")
    default List<MilestoneEntry> milestones() {
        return DefaultTrack.LIST;
    }

    /** One milestone of the track. */
    @ConfigSpec
    interface MilestoneEntry {

        @Order(1)
        @Name("ID")
        @Key("key")
        @Explain("Identity, and the primary key in smp_milestone. Renaming one with progress is refused on reload.")
        default String key() {
            return "";
        }

        @Order(2)
        @Name("Unlocks")
        @Key("unlocks")
        @Explain("BORDER, NETHER, END or NOTHING.")
        @AllowedValues({"BORDER", "NETHER", "END", "NOTHING"})
        default String unlocks() {
            return "NOTHING";
        }

        @Order(3)
        @Name("Border diameter")
        @Key("border-diameter")
        @Explain("The border diameter this milestone sets; read only when unlocks is BORDER.")
        default int borderDiameter() {
            return 0;
        }

        @Order(4)
        @Name("Objective pot")
        @Key("objective-pot")
        @Explain("The aura pot of EACH objective below, derived from community play hours.")
        default int objectivePot() {
            return 0;
        }

        @Order(5)
        @Name("Unlocked by admin")
        @Key("admin-unlocked")
        @Explain("Opened by an admin rather than by objectives; true only for the opening milestone.")
        default boolean adminUnlocked() {
            return false;
        }

        @Order(6)
        @Name("Objectives")
        @Key("objectives")
        @Explain("All must finish before this milestone unlocks; exactly one is an ADVANCEMENT, the gate.")
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
        @Explain("HAND_IN, STATISTIC or ADVANCEMENT; decides which of the fields below apply.")
        @AllowedValues({"HAND_IN", "STATISTIC", "ADVANCEMENT"})
        default String type() {
            return "HAND_IN";
        }

        @Order(3)
        @Name("Role")
        @Key("role")
        @Explain("What this objective is for, e.g. gathering. Never read by the engine; it keeps categories apart.")
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

        @Order(5)
        @Name("Items")
        @Key("items")
        @Explain("HAND_IN only: items, any of which counts. An unknown one stops the plugin.")
        @Refers(Refers.To.ITEM)
        default List<String> items() {
            return List.of();
        }

        @Order(6)
        @Name("Statistic")
        @Key("statistic")
        @Explain("STATISTIC only: the statistic summed across players.")
        @Refers(Refers.To.STATISTIC)
        default String statistic() {
            return "";
        }

        @Order(7)
        @Name("Subjects")
        @Key("subjects")
        @Explain("STATISTIC only: the materials or entity types the statistic is summed over; may be empty.")
        @Refers(value = Refers.To.SUBJECT, dependsOn = "statistic")
        default List<String> subjects() {
            return List.of();
        }

        @Order(8)
        @Name("Advancement")
        @Key("advancement")
        @Explain("ADVANCEMENT only: the advancement key, e.g. minecraft:story/mine_diamond.")
        @Refers(Refers.To.ADVANCEMENT)
        default String advancement() {
            return "";
        }
    }
}
