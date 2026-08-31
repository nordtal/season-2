package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

import java.util.List;

/**
 * {@code plugins/smp/milestones.yml} - the track, and the one file the rest of this module reads
 * the season's shape out of.
 *
 * <h2>Why a config file and not code</h2>
 * docs/smp.md#where-a-milestone-is-defined: "in a YAML config file, reloadable with a command. The
 * definition is versioned in the repository; the <em>progress</em> lives in the database. Adding a
 * milestone is a file edit plus {@code /smp reload} - no release, no restart." That is not a
 * convenience: appending a milestone is the <b>planned response to a track that finishes early</b>,
 * because scaling targets to the live player count was rejected on the grounds that a target which
 * moves overnight reads as a shifted goalpost.
 *
 * <h2>The file format, decided 2026-09-01</h2>
 * The table in docs/smp.md#the-track is the <em>content</em>, not a schema, so the shape below is
 * this session's choice. Three things about it are worth the sentence each:
 *
 * <ul>
 *   <li><b>A list of milestones, each with a list of objectives</b> - two levels of nesting through
 *       jcore, which is the repository's standing config system. The alternative, a flat list of
 *       objectives each naming its milestone, would have been easier for the loader and worse for
 *       every human being who ever edits this file: the thing being edited is a milestone, and its
 *       objectives belong inside it.</li>
 *   <li><b>One record shape for all three objective types</b>, with the fields that do not apply
 *       left empty. A jcore spec is an interface with a fixed set of keys and cannot be
 *       polymorphic; {@code TrackShape} is what makes the legal combinations legal, and it says
 *       plainly when a field belongs to another type, because a leftover {@code items} list is what
 *       a half-finished type change looks like.</li>
 *   <li><b>The pot is per milestone, not per objective.</b> It is derived rather than chosen -
 *       {@code pot = round((budget ÷ objectives) × 5, to 10)} - and a per-objective pot would let
 *       that derivation drift one objective at a time until nobody could say what the ramp was.</li>
 * </ul>
 *
 * <h2>Every number here is a default</h2>
 * docs/smp.md is explicit that the <em>rules</em> that produced the track are the decision and the
 * numbers they produced are defaults. The items and advancements in particular are one worked
 * example each and are <b>expected to be corrected in the diff</b>; what must survive a correction
 * is the shape - how many objectives a milestone has, which type each is, and which role it serves.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  smp - the milestone track",
        "-------------------------------------------------------------------",
        "The community unlocks its own world by finishing shared objectives.",
        "This file is the whole of what those objectives are; the PROGRESS",
        "lives in the database (smp_milestone, smp_objective), and the two are",
        "matched by key.",
        "",
        "RELOAD WITH /smp reload. The reload is validated against the stored",
        "progress and is REFUSED if it would orphan any: renaming a milestone",
        "or an objective key, deleting one that has progress, or changing an",
        "objective's type. What it explicitly DOES allow is lowering the",
        "`target` of an objective that has not completed - that is the finest",
        "of the three escape hatches for an objective that turns out to be",
        "impossible, and if the collected progress is already at or above the",
        "new target the objective completes at once and pays its FULL pot.",
        "",
        "APPENDING A MILESTONE IS THE PLANNED ANSWER TO A TRACK THAT FINISHES",
        "EARLY. The track is sized against 8 active players in week three, not",
        "against the 15-30 who show up on day one, so a strong turnout gets",
        "through it faster than the estimates - and the answer to that is one",
        "more milestone at the end, never a target that moves overnight.",
        "",
        "Border sizes are DIAMETERS, because that is what Minecraft's world",
        "border takes.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_SMP_MILESTONES_<PATH>. Nobody should ever want to: a list of",
        "milestones is not something to express in the environment."
})
public interface MilestonesSpec {

    @Order(1)
    @Key("milestones")
    @Comment({
            "The track, in order. The order in this file IS the order of the season - there is no",
            "ordering column in the database, deliberately, because storing it would create a",
            "second answer that a file edit could contradict.",
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
            "The Nether and the End are their own milestones and carry no border step. The",
            "dimension is the reward and it is larger than any number; pairing it with a border",
            "step would chain the one to the other."
    })
    default List<MilestoneEntry> milestones() {
        return DefaultTrack.LIST;
    }

    /** One milestone of the track. */
    @ConfigSpec
    interface MilestoneEntry {

        @Order(1)
        @Key("key")
        @Comment("Identity, and the primary key in smp_milestone. Renaming one orphans its progress.")
        default String key() {
            return "";
        }

        @Order(2)
        @Key("unlocks")
        @Comment("BORDER, NETHER, END or NOTHING.")
        default String unlocks() {
            return "NOTHING";
        }

        @Order(3)
        @Key("border-diameter")
        @Comment("The Nordtal border this milestone sets, as a DIAMETER. Read only when unlocks is BORDER.")
        default int borderDiameter() {
            return 0;
        }

        @Order(4)
        @Key("objective-pot")
        @Comment({
                "The aura pot of EACH objective below, not of the milestone as a whole.",
                "Derived rather than chosen: pot = round((budget / objectives) * 5, to 10). The",
                "budget is COMMUNITY play hours against a pessimistic population - the final",
                "milestone is 8 players x 14 days x 1.5 h = 170 hours - so retuning this is",
                "arithmetic rather than a fresh argument. There is deliberately no minimum pot:",
                "a minimum flattens the ramp the track exists to create."
        })
        default int objectivePot() {
            return 0;
        }

        @Order(5)
        @Key("admin-unlocked")
        @Comment("Opened by an admin rather than by objectives. True for `departure` alone.")
        default boolean adminUnlocked() {
            return false;
        }

        @Order(6)
        @Key("objectives")
        @Comment({
                "All of them must be finished before the milestone unlocks. Exactly ONE of them",
                "must be an ADVANCEMENT - the participation gate. It is the only type that counts",
                "distinct players rather than a total, and therefore the only one three",
                "industrious people cannot finish alone; it is also the only one whose progress",
                "survives a player leaving, because progress lives in the database and is never",
                "recomputed.",
                "",
                "The opening two milestones have none at all."
        })
        default List<ObjectiveEntry> objectives() {
            return List.of();
        }
    }

    /** One objective. Which of the fields below apply depends on `type`. */
    @ConfigSpec
    interface ObjectiveEntry {

        @Order(1)
        @Key("key")
        @Comment("Unique within its milestone, and what smp_objective.key stores. Never rename one with progress.")
        default String key() {
            return "";
        }

        @Order(2)
        @Key("type")
        @Comment({
                "HAND_IN     items delivered at the spawn NPC; a share is what that player handed in.",
                "STATISTIC   a vanilla statistic summed across players; a share is that player's own",
                "            increase since the objective started. ACTIVE STATISTICS ONLY - never",
                "            distance walked or time played, which would pay every present player a",
                "            share simply for being online.",
                "ADVANCEMENT how many DISTINCT players earned it; a share is 1 or 0."
        })
        default String type() {
            return "HAND_IN";
        }

        @Order(3)
        @Key("role")
        @Comment({
                "What this objective is FOR: gathering, mining, combat, production, exploration,",
                "participation. The engine never reads it and it is required anyway - it is what",
                "stops a correction from accidentally producing four mining objectives, which only",
                "works if it is there to read in the diff."
        })
        default String role() {
            return "";
        }

        @Order(4)
        @Key("target")
        @Comment({
                "What has to be reached. For ADVANCEMENT it is a count of DISTINCT PLAYERS, and it",
                "falls across the track (10, 10, 8, 8, 6, 5) because the population does.",
                "",
                "Lowering this on a live objective is the first escape hatch and is always allowed;",
                "if the collected progress is already at or above the new value, the objective",
                "completes on reload and pays its full pot. Changing it on one that has already",
                "completed is refused - that would rewrite the arithmetic behind aura already paid."
        })
        default long target() {
            return 1L;
        }

        @Order(5)
        @Key("items")
        @Comment({
                "HAND_IN only. Any of these counts, which is how 'logs, any kind' and 'bulk",
                "building blocks' are expressed. Bukkit material names; they are resolved once at",
                "startup and an unknown one stops the plugin with the name in the message."
        })
        default List<String> items() {
            return List.of();
        }

        @Order(6)
        @Key("statistic")
        @Comment("STATISTIC only. A Bukkit statistic name, e.g. MINE_BLOCK, KILL_ENTITY, CRAFT_ITEM.")
        default String statistic() {
            return "";
        }

        @Order(7)
        @Key("subjects")
        @Comment({
                "STATISTIC only, and summed. The materials or entity types the statistic is counted",
                "over - a list, because the track asks for 'hostile mobs' and 'raider', which are",
                "groups rather than one entity. May be empty for a statistic that has no",
                "substatistic at all."
        })
        default List<String> subjects() {
            return List.of();
        }

        @Order(8)
        @Key("advancement")
        @Comment("ADVANCEMENT only. The advancement key, e.g. minecraft:story/mine_diamond.")
        default String advancement() {
            return "";
        }
    }
}
