package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.AllowedValues;
import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.Order;
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
    @Comment({
        "The track, in order. The order in this file IS the order of the season.",
        "",
        "Each entry:",
        "  key              the milestone's identity, and its primary key in smp_milestone.",
        "                   NEVER RENAME ONE that has progress; the reload will refuse it.",
        "  unlocks          BORDER, NETHER, END or NOTHING.",
        "  border-diameter  the Nordtal border this milestone sets. Read only for BORDER.",
        "  objective-pot    the aura pot of EACH of this milestone's objectives.",
        "  admin-unlocked   opened by an admin rather than by objectives. True for `departure`",
        "                   alone, which is the opening expansion at the start of the season.",
        "  objectives       what has to be finished; ALL of them, before the milestone unlocks.",
        "",
        "The Nether and the End are their own milestones and carry no border step."
    })
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
        @Comment("Identity, and the primary key in smp_milestone. Renaming one orphans its progress.")
        @Explain("Identity, and the primary key in smp_milestone. Renaming one with progress is refused on reload.")
        default String key() {
            return "";
        }

        @Order(2)
        @Name("Unlocks")
        @Key("unlocks")
        @Comment("BORDER, NETHER, END or NOTHING.")
        @Explain("BORDER, NETHER, END or NOTHING.")
        @AllowedValues({"BORDER", "NETHER", "END", "NOTHING"})
        default String unlocks() {
            return "NOTHING";
        }

        @Order(3)
        @Name("Border diameter")
        @Key("border-diameter")
        @Comment("The Nordtal border this milestone sets, as a DIAMETER. Read only when unlocks is BORDER.")
        @Explain("The border diameter this milestone sets; read only when unlocks is BORDER.")
        default int borderDiameter() {
            return 0;
        }

        @Order(4)
        @Name("Objective pot")
        @Key("objective-pot")
        @Comment({
            "The aura pot of EACH objective below, not of the milestone as a whole.",
            "Derived: pot = round((community play hours / objectives) * 5, to 10). No minimum."
        })
        @Explain("The aura pot of EACH objective below, derived from community play hours.")
        default int objectivePot() {
            return 0;
        }

        @Order(5)
        @Name("Unlocked by admin")
        @Key("admin-unlocked")
        @Comment("Opened by an admin rather than by objectives. True for `departure` alone.")
        @Explain("Opened by an admin rather than by objectives; true only for the opening milestone.")
        default boolean adminUnlocked() {
            return false;
        }

        @Order(6)
        @Name("Objectives")
        @Key("objectives")
        @Comment({
            "All of them must be finished before the milestone unlocks. Exactly ONE must be an",
            "ADVANCEMENT, the participation gate, since it counts distinct players. The opening two have none."
        })
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
        @Comment("Unique within its milestone, and what smp_objective.key stores. Never rename one with progress.")
        @Explain("Unique within its milestone. Never rename one with progress.")
        default String key() {
            return "";
        }

        @Order(2)
        @Name("Type")
        @Key("type")
        @Comment({
            "HAND_IN     items delivered at the spawn NPC; a share is what that player handed in.",
            "STATISTIC   a vanilla statistic summed across players; a share is that player's own",
            "            increase since the objective started. ACTIVE STATISTICS ONLY, never",
            "            distance walked or time played.",
            "ADVANCEMENT how many DISTINCT players earned it; a share is 1 or 0."
        })
        @Explain("HAND_IN, STATISTIC or ADVANCEMENT; decides which of the fields below apply.")
        @AllowedValues({"HAND_IN", "STATISTIC", "ADVANCEMENT"})
        default String type() {
            return "HAND_IN";
        }

        @Order(3)
        @Name("Role")
        @Key("role")
        @Comment({
            "What this objective is FOR: gathering, mining, combat, production, exploration,",
            "participation. Never read by the engine; it keeps an edit from duplicating a category."
        })
        @Explain("What this objective is for, e.g. gathering. Never read by the engine; it keeps categories apart.")
        default String role() {
            return "";
        }

        @Order(4)
        @Name("Target")
        @Key("target")
        @Comment({
            "What has to be reached. For ADVANCEMENT it is a count of DISTINCT PLAYERS.",
            "Lowering it on a live objective is always allowed; changing a completed one is refused."
        })
        @Explain(
                "Lowering this on a live objective is always allowed; changing it on one that already completed is refused.")
        default long target() {
            return 1L;
        }

        @Order(5)
        @Name("Items")
        @Key("items")
        @Comment({
            "HAND_IN only. Bukkit material names, any of which counts.",
            "An unknown one stops the plugin at startup with the name in the message."
        })
        @Explain("HAND_IN only: Bukkit material names, any of which counts. An unknown name stops the plugin.")
        default List<String> items() {
            return List.of();
        }

        @Order(6)
        @Name("Statistic")
        @Key("statistic")
        @Comment("STATISTIC only. A Bukkit statistic name, e.g. MINE_BLOCK, KILL_ENTITY, CRAFT_ITEM.")
        @Explain("STATISTIC only: a Bukkit statistic name, e.g. MINE_BLOCK.")
        default String statistic() {
            return "";
        }

        @Order(7)
        @Name("Subjects")
        @Key("subjects")
        @Comment({
            "STATISTIC only, and summed. The materials or entity types the statistic is counted",
            "over. Empty for a statistic that has no substatistic."
        })
        @Explain("STATISTIC only: the materials or entity types the statistic is summed over; may be empty.")
        default List<String> subjects() {
            return List.of();
        }

        @Order(8)
        @Name("Advancement")
        @Key("advancement")
        @Comment("ADVANCEMENT only. The advancement key, e.g. minecraft:story/mine_diamond.")
        @Explain("ADVANCEMENT only: the advancement key, e.g. minecraft:story/mine_diamond.")
        default String advancement() {
            return "";
        }
    }
}
