package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;

import java.util.List;

/**
 * {@code plugins/smp/config.yml} - everything about the SMP that is neither the track
 * ({@link MilestonesSpec}) nor a database credential.
 *
 * <p><b>Six of the values below were listed in docs/smp.md#still-open as needing a decision, and
 * every one of them is proposed here as a default rather than argued in prose</b>, which is what
 * that document asks for: the duel loadouts, the advancement awards, the "embarrassing" death
 * causes, the wheel's prize pool and weights, and the hunger games winner's head start. They are
 * proposals. Retuning any of them is an edit to this file and never a release.
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  smp - the season 2 SMP",
        "-------------------------------------------------------------------",
        "The track itself is NOT here: it lives in milestones.yml and is",
        "reloaded on its own with /smp reload, because it is edited on a",
        "completely different rhythm from everything below.",
        "",
        "EVERY NUMBER IN THIS FILE IS A PROPOSAL. docs/smp.md gathers them",
        "under 'Numbers that are proposals, not decisions' for exactly that",
        "reason - what was decided is the shape, and the values are defaults",
        "chosen to be reasonable.",
        "",
        "Every setting can be overridden with an environment variable named",
        "NORDTAL_SMP_<PATH>, with '.' and '-' both becoming '_'."
})
public interface SmpSpec {

    // ---------------------------------------------------------------- worlds

    @Order(1)
    @Key("world-nordtal")
    @Comment({
            "The permanent build world: the spawn, the tavern, the balloon, the duel platforms.",
            "",
            "PRE-GENERATED ONCE, TO ITS FINAL BORDER OF 4000, BEFORE THE PHASE OPENS. A milestone",
            "unlock then only moves a number and never starts a generator. How long that costs in",
            "wall clock and disk has to be measured on the real host before the phase is scheduled -",
            "see docs/smp.md#worlds."
    })
    default String worldNordtal() {
        return "nordtal";
    }

    @Order(2)
    @Key("world-farm")
    @Comment({
            "The farm world, regenerated daily with a new seed. Everything in it dies with the",
            "reset - chests, graves and POIs - and that is stated plainly to players rather than",
            "softened.",
            "",
            "The reset is a SWAP, not a rebuild in place: tomorrow's world is pre-generated during",
            "the day into a separate folder and the reset itself is an unload, a rename and a load.",
            "A pre-generation that has not finished POSTPONES the reset rather than swapping in a",
            "half-built world."
    })
    default String worldFarm() {
        return "farm";
    }

    @Order(3)
    @Key("world-nether")
    @Comment("The Nether. Fixed border, generated once before its own milestone unlocks.")
    default String worldNether() {
        return "nordtal_nether";
    }

    @Order(4)
    @Key("world-end")
    @Comment("The End. Entered by balloon only - a stronghold's End portal never activates.")
    default String worldEnd() {
        return "nordtal_the_end";
    }

    @Order(5)
    @Key("farm-world-border-diameter")
    @Comment({
            "A DIAMETER, like every border in this repository, because that is what Minecraft's",
            "world border takes.",
            "",
            "This is the number to lower if the daily pre-generation turns out to cost tick time",
            "with players online - which docs/smp.md calls the single biggest technical risk in the",
            "concept. Halving it quarters the work."
    })
    default int farmWorldBorderDiameter() {
        return 2000;
    }

    @Order(6)
    @Key("nether-border-diameter")
    @Comment({
            "Deliberately several times larger than the 1:8 mapping requires - a 4000 overworld",
            "needs only 500 blocks of Nether to be fully reachable. It costs nothing, because",
            "Minecraft handles portal search and linking beyond a border anyway, and it leaves room",
            "for any milestone appended above 4000 without a second pre-generation."
    })
    default int netherBorderDiameter() {
        return 2000;
    }

    @Order(7)
    @Key("end-border-diameter")
    @Comment("The End's fixed border.")
    default int endBorderDiameter() {
        return 2000;
    }

    @Order(8)
    @Key("border-centre-x")
    @Comment("The Nordtal border's centre. The working value from docs/smp.md#world-rules.")
    default int borderCentreX() {
        return 106;
    }

    @Order(9)
    @Key("border-centre-z")
    @Comment("See border-centre-x.")
    default int borderCentreZ() {
        return 88;
    }

    @Order(10)
    @Key("border-expansion-blocks-per-second")
    @Comment({
            "How fast a milestone's border expansion travels - roughly a quarter to a half of",
            "walking speed, which makes the final expansion's 1550-block edge take between a",
            "quarter of an hour and half an hour to arrive. That is a ceremony rather than a",
            "hiccup, and it is meant to be."
    })
    default double borderExpansionBlocksPerSecond() {
        return 1.5;
    }

    @Order(11)
    @Key("farm-reset-time")
    @Comment("Local time of day the farm world is swapped, HH:mm. Also available on command.")
    default String farmResetTime() {
        return "05:00";
    }

    @Order(12)
    @Key("farm-reset-warning-minutes")
    @Comment("How far ahead the reset is announced, in chat and on the HUD, in every language.")
    default List<Integer> farmResetWarningMinutes() {
        return List.of(30, 10, 5, 1);
    }

    // ------------------------------------------------- world generation plumbing

    @Order(13)
    @Key("required-datapacks")
    @Comment({
            "The world-generation datapacks that MUST be installed and enabled, checked at enable.",
            "Matched against the names Paper reports, case-insensitively, as a substring - Paper",
            "prefixes a zip with 'file/', so 'Terralith' matches 'file/Terralith_26.2_v2.6.4.zip'.",
            "",
            "THE PLUGIN ONLY CHECKS. It cannot install them, and that is not a gap: datapacks are",
            "read once at server start, into registries the whole server shares, so a pack dropped",
            "in afterwards changes nothing until the next restart. Installing them is the",
            "container entrypoint's job (deploy/minecraft/entrypoint.sh), where the version is",
            "pinned and checksummed.",
            "",
            "MEASURED ON PAPER 26.2 BUILD 121, 2026-09-01, because this design first assumed the",
            "opposite: datapacks are SERVER-GLOBAL and are read only from <level-name>/datapacks/.",
            "A probe pack placed in a secondary world's own datapacks/ folder was never listed -",
            "not at start, not after creating that world, not after refreshPacks(). There is no",
            "per-world datapack API: DatapackManager hangs off Server, not World, and WorldCreator",
            "has no datapack option. So every world this server generates - Nordtal, the farm",
            "world, the Nether and the End - gets the same packs, and the farm world's nightly",
            "regeneration inherits them without anything being copied.",
            "",
            "Why this is worth failing the start over: a world generated without its packs is",
            "vanilla terrain permanently, because terrain is never re-rolled once it is on disk.",
            "For the farm world that is one bad day; for Nordtal it is the whole season."
    })
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }

    @Order(15)
    @Key("pregeneration-pattern")
    @Comment({
            "The order Chunky walks the chunks in. 'concentric' works outwards from the centre, so",
            "an interrupted run still leaves a usable middle; 'loop', 'spiral' and 'csv' are the",
            "other shapes Chunky accepts.",
            "",
            "Chunky is a REQUIRED plugin (paper-plugin.yml). The daily reset waits for its",
            "completion event and postpones itself rather than swapping in a half-built world, so",
            "without Chunky the farm world would quietly stop resetting instead of failing."
    })
    default String pregenerationPattern() {
        return "concentric";
    }

    @Order(16)
    @Key("farm-world-staging-suffix")
    @Comment({
            "Tomorrow's farm world is generated under the live name plus this suffix, and renamed",
            "onto the live name at the reset. Verified on Paper 26.2 build 121 on 2026-09-01:",
            "unloading a world really does release its folder, the folder can be deleted, another",
            "renamed into its place, and the SAME name loaded again - three rounds, no restart.",
            "",
            "That is why there is one world name in this file and not a pair alternating daily."
    })
    default String farmWorldStagingSuffix() {
        return "-next";
    }

    @Order(17)
    @Key("farm-world-retired-suffix")
    @Comment({
            "Yesterday's farm world is RENAMED to this suffix during the swap and deleted",
            "afterwards, off the main thread.",
            "",
            "The rename is what keeps the swap short. Renaming a directory is one filesystem",
            "operation and takes about as long whether the directory holds one file or a hundred",
            "thousand; deleting a farm world is gigabytes of unlinking. The measured 15 ms swap",
            "window of the 2026-09-01 drill was on tiny test worlds where deleting happened to be",
            "instant - on a real one, deleting in the swap would freeze the server for the length",
            "of an rm -rf, which is precisely the window this design exists to avoid.",
            "",
            "A leftover folder with this suffix means a previous delete was interrupted. It is",
            "cleaned up at the next start and is never loaded as a world."
    })
    default String farmWorldRetiredSuffix() {
        return "-old";
    }

    // ---------------------------------------------------------------- the balloons

    @Order(18)
    @Key("balloons")
    @Comment({
            "Where the balloons stand. Stepping into one of these boxes opens the travel GUI; the",
            "balloon itself is a model on a barrier-block floor, and this is the volume above it.",
            "",
            "One box per world that has a balloon: Nordtal, the farm world and the Nether. The End",
            "deliberately has none - it is entered by balloon and left through the vanilla exit",
            "portal, which does not work until the dragon is dead, and that one-way trip is the",
            "point of unlocking it together.",
            "",
            "NORDTAL'S BALLOON HAS A HARD CONSTRAINT AND IT IS NOT DECORATIVE: it must stand",
            "outside radius 10 and inside radius 21.5 of the border centre. That is what makes the",
            "opening border of 20 withhold travel and the first expansion to 43 hand it over. Every",
            "other social structure - tavern, NPC, both boards, both duel platforms - belongs inside",
            "radius 10, so the only thing the opening minutes withhold is the balloon. The plugin",
            "checks this at enable and refuses to start if it is wrong, because a balloon on the",
            "wrong side of that line makes the season's first milestone mean nothing.",
            "",
            "The coordinates below are placeholders."
    })
    default List<BalloonSpec> balloons() {
        return DefaultSmp.BALLOONS;
    }

    /** One balloon's volume: the world it stands in and the box a player steps into. */
    @ConfigSpec
    interface BalloonSpec {

        @Order(1) @Key("world")
        @Comment("Which world this balloon stands in.")
        default String world() {
            return "nordtal";
        }

        @Order(2) @Key("min-x") default int minX() { return 0; }

        @Order(3) @Key("min-y") default int minY() { return 0; }

        @Order(4) @Key("min-z") default int minZ() { return 0; }

        @Order(5) @Key("max-x") default int maxX() { return 0; }

        @Order(6) @Key("max-y") default int maxY() { return 0; }

        @Order(7) @Key("max-z") default int maxZ() { return 0; }
    }

    // ---------------------------------------------------------------- the boards

    @Order(19)
    @Key("boards")
    @Comment({
            "The two boards at the spawn: the current milestone at a glance, and the aura",
            "leaderboard.",
            "",
            "RENDERED PER PLAYER, IN THEIR OWN LANGUAGE. Each entry below is an anchor, not a",
            "display: the plugin spawns one Text Display per board PER VIEWER at that position and",
            "hides it from everyone else, which is the only way two people standing side by side can",
            "read the same board in two languages. With a handful of players that is a handful of",
            "entities; it is not a technique that would scale to a hundred, and it does not have to.",
            "",
            "kind is OBJECTIVE or AURA. Anything else stops the load.",
            "",
            "The coordinates are placeholders until the spawn is built."
    })
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }

    /** One board's anchor: which board, where it hangs, and which way it faces. */
    @ConfigSpec
    interface BoardSpec {

        @Order(1) @Key("kind")
        @Comment("OBJECTIVE or AURA.")
        default String kind() {
            return "OBJECTIVE";
        }

        @Order(2) @Key("world") default String world() { return "nordtal"; }

        @Order(3) @Key("x") default double x() { return 0.0; }

        @Order(4) @Key("y") default double y() { return 70.0; }

        @Order(5) @Key("z") default double z() { return 0.0; }

        @Order(6) @Key("yaw")
        @Comment("Which way the board faces, in degrees. 0 is south, 90 west, 180 north, 270 east.")
        default float yaw() { return 0.0f; }

        @Order(7) @Key("width")
        @Comment({
                "How wide the frame is drawn, in pixels of the board's own text - 32 to 240.",
                "",
                "This is a number somebody picks by looking at the board, not one the plugin can",
                "work out: the width of a line of text is decided by the vanilla font's per-",
                "character advances, which live in the client and not in this repository. A line",
                "that outgrows the frame draws over the right-hand edge, which is visible at once",
                "and is fixed here without a release. See BoardFrame."
        })
        default int width() { return 180; }
    }

    // ---------------------------------------------------------------- the duel platforms

    @Order(20)
    @Key("duel-platforms")
    @Comment({
            "The two 3x3 platforms at the spawn. Two players standing on the same one at the same",
            "time are taken into an arena.",
            "",
            "type is SWORD or BOW and picks which loadout both fighters get. Anything else stops",
            "the load.",
            "",
            "The coordinates are placeholders until the spawn is built. Both platforms belong",
            "INSIDE radius 10 of the border centre with everything else social - the balloon is the",
            "only thing the opening border withholds."
    })
    default List<DuelPlatformSpec> duelPlatforms() {
        return DefaultSmp.DUEL_PLATFORMS;
    }

    /** One duel platform: which loadout it hands out, and the box a player has to stand in. */
    @ConfigSpec
    interface DuelPlatformSpec {

        @Order(1) @Key("type")
        @Comment("SWORD or BOW.")
        default String type() {
            return "SWORD";
        }

        @Order(2) @Key("world") default String world() { return "nordtal"; }

        @Order(3) @Key("min-x") default int minX() { return 0; }

        @Order(4) @Key("min-y") default int minY() { return 0; }

        @Order(5) @Key("min-z") default int minZ() { return 0; }

        @Order(6) @Key("max-x") default int maxX() { return 0; }

        @Order(7) @Key("max-y") default int maxY() { return 0; }

        @Order(8) @Key("max-z") default int maxZ() { return 0; }
    }

    @Order(21)
    @Key("duel-arena-base-y")
    @Comment({
            "The height of the lowest arena. Further concurrent duels stack above it.",
            "",
            "Well above anything anybody builds: the arenas are placed by the plugin and taken away",
            "again, and a stack that reached into the skyline would eventually land on somebody's",
            "tower."
    })
    default int duelArenaBaseY() {
        return 200;
    }

    @Order(22)
    @Key("duel-arena-spacing")
    @Comment("Vertical distance between stacked arenas. Has to exceed the arena's own height.")
    default int duelArenaSpacing() {
        return 16;
    }

    @Order(23)
    @Key("duel-arena-radius")
    @Comment({
            "Half the arena's floor, in blocks - a radius of 7 is a 15x15 floor. Big enough that a",
            "bow duel is not a knife fight, small enough that neither fighter can simply run."
    })
    default int duelArenaRadius() {
        return 7;
    }

    // ---------------------------------------------------------------- the wheel of fortune

    @Order(24)
    @Key("wheel-regions")
    @Comment({
            "Where the wheel of fortune stands in the tavern. Right-clicking inside one of these",
            "boxes spins it - one free spin per calendar day, plus whatever contributing to",
            "objectives has earned.",
            "",
            "Same box shape as spawn-regions and balloons, and for the same reason: a spawn is a box",
            "you may not build in, a balloon is a box that opens the travel GUI, and this is a box",
            "that spins a wheel. Three nearly identical settings would have drifted apart.",
            "",
            "IT COSTS NO AURA. Aura is recognition, not currency, and the moment it buys something",
            "it stops being recognition.",
            "",
            "The coordinates are placeholders until the tavern is built."
    })
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    // ---------------------------------------------------------------- the spawn NPC

    @Order(25)
    @Key("npc")
    @Comment({
            "The figure in the tavern. Click it to open the objective list and hand items in.",
            "",
            "It is a MANNEQUIN - a vanilla Paper 26.2 entity with a real player skin. Decided",
            "2026-09-01, and it needed no dependency at all: a Mannequin is a LivingEntity and not a",
            "Mob, so it has no AI, never despawns, never wanders and cannot be killed. The three",
            "options docs/smp.md used to weigh - a villager with its AI off, a custom entity,",
            "Citizens - were all worse than something the server already ships.",
            "",
            "skin-name is a Minecraft account whose skin the figure wears, resolved at start. Leave",
            "it empty for the default skin. A later 3D model would replace how the NPC is DRAWN and",
            "nothing about how it is clicked.",
            "",
            "The coordinates are placeholders until the tavern is built."
    })
    default NpcSpec npc() {
        return DefaultSmp.NPC;
    }

    /** Where the spawn NPC stands, what it is called, and whose skin it wears. */
    @ConfigSpec
    interface NpcSpec {

        @Order(1) @Key("world") default String world() { return "nordtal"; }

        @Order(2) @Key("x") default double x() { return 106.5; }

        @Order(3) @Key("y") default double y() { return 68.0; }

        @Order(4) @Key("z") default double z() { return 92.5; }

        @Order(5) @Key("yaw")
        @Comment("Which way it faces, in degrees. 0 is south, 90 west, 180 north, 270 east.")
        default float yaw() { return 180.0f; }

        @Order(6) @Key("skin-name")
        @Comment("A Minecraft account name whose skin to wear, or empty for the default.")
        default String skinName() { return ""; }

        @Order(7) @Key("name")
        @Comment("The label above it. Empty for none.")
        default String name() { return "Nordtal"; }
    }

    // ---------------------------------------------------------------- spawn protection

    @Order(26)
    @Key("spawn-regions")
    @Comment({
            "The protected zones: no building, no breaking, no interaction with blocks you do not",
            "own, no explosions. A list of boxes per world, and NOT WorldGuard - what is needed is",
            "a handful of event handlers over a few fixed boxes, not a region system with claims,",
            "flags and ownership, and this avoids a large third-party dependency whose Minecraft",
            "26.2 availability is unverified.",
            "",
            "Boxes are inclusive on both corners and are checked in order; the first one that",
            "contains a block wins. THE COORDINATES BELOW ARE PLACEHOLDERS - the spawn build does",
            "not exist yet, and the one hard geometric constraint on it is that the balloon stands",
            "outside radius 10 and inside radius 21.5 of the border centre, so that border 20",
            "withholds the farm world and the opening expansion to 43 hands it over."
    })
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }

    /** One protected box. */
    @ConfigSpec
    interface SpawnRegionSpec {

        @Order(1) @Key("world")
        @Comment("Which world the box is in.")
        default String world() {
            return "";
        }

        @Order(2) @Key("min-x") default int minX() { return 0; }

        @Order(3) @Key("min-y") default int minY() { return -64; }

        @Order(4) @Key("min-z") default int minZ() { return 0; }

        @Order(5) @Key("max-x") default int maxX() { return 0; }

        @Order(6) @Key("max-y") default int maxY() { return 320; }

        @Order(7) @Key("max-z") default int maxZ() { return 0; }
    }

    // ---------------------------------------------------------------- aura

    @Order(27)
    @Key("death-penalty")
    @Comment({
            "What an ordinary death costs, as a POSITIVE number that is subtracted at the point of",
            "use. Anywhere except the duel arena.",
            "",
            "Aura is meant to be a number with risk in it rather than a collection meter that only",
            "ever rises. Against a season total of roughly 2480 aura in objective pots and a top",
            "contributor around 350, fifty deaths at 5 are a meaningful drag without being able to",
            "bury a hard-working player."
    })
    default int deathPenalty() {
        return 5;
    }

    @Order(28)
    @Key("death-penalty-listed")
    @Comment("What one of the causes below costs instead. Also a positive number.")
    default int deathPenaltyListed() {
        return 20;
    }

    @Order(29)
    @Key("death-causes-listed")
    @Comment({
            "The 'embarrassing' deaths, one of docs/smp.md#still-open's open points, PROPOSED HERE",
            "as a default. Damage-type keys, matched case-insensitively and with or without the",
            "minecraft: namespace.",
            "",
            "The band this list is trying to describe: a death nobody else caused and that a moment",
            "of attention would have prevented. Falling into your own lava, standing in your own",
            "fire, walking into a cactus, drowning in water you swam into, suffocating in a block",
            "you placed. Deliberately NOT here: anything a mob or another player did, anything to",
            "do with the world border or the void, and starvation - the first is not embarrassing,",
            "the second happens to everybody exploring a fresh border, and the third is usually a",
            "long trip gone wrong rather than a lapse.",
            "",
            "Dying in the End during the dragon fight stays an ORDINARY death, which is deliberate:",
            "until the dragon falls, dying is the only way home."
    })
    default List<String> deathCausesListed() {
        return List.of("lava", "in_fire", "on_fire", "cactus", "drown", "in_wall",
                "sweet_berry_bush", "hot_floor", "campfire", "stalagmite");
    }

    @Order(30)
    @Key("duel-stake")
    @Comment({
            "What a duel moves. The winner takes exactly what the loser pays, so a duel never",
            "creates or destroys aura - and the arena is the one place a death costs nothing",
            "beyond it, because the stake has already settled the fight."
    })
    default int duelStake() {
        return 10;
    }

    @Order(31)
    @Key("concurrent-duel-limit")
    @Comment("How many arenas may be stacked above the spawn at once. Beyond it, players queue.")
    default int concurrentDuelLimit() {
        return 3;
    }

    @Order(32)
    @Key("advancement-awards")
    @Comment({
            "The advancements that pay aura, once each per player. docs/smp.md#still-open lists",
            "this as open and sets the band at 2-10; PROPOSED HERE as a default, and the loader",
            "refuses anything outside the band - a value above it would let one advancement",
            "outweigh a whole objective.",
            "",
            "Chosen so the number tracks how much of the game the advancement actually represents,",
            "not how hard it is to look up: 2 for the first hours, 5 for a real trip, 8 for a",
            "project, 10 for the two that take a season. This is a CURATED list and not every",
            "advancement - the point is to reward the shape of a playthrough, not to pay for",
            "ticking boxes."
    })
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }

    /** One advancement and what it pays. */
    @ConfigSpec
    interface AdvancementAwardSpec {

        @Order(1) @Key("advancement")
        @Comment("The advancement key, e.g. minecraft:story/mine_diamond.")
        default String advancement() {
            return "";
        }

        @Order(2) @Key("aura")
        @Comment("2 to 10. Anything outside that band stops the load.")
        default int aura() {
            return 2;
        }
    }

    // ---------------------------------------------------------------- prestige

    @Order(33)
    @Key("prestige-threshold-hours")
    @Comment({
            "The thirteen crest tiers, in hours of NETWORK-WIDE online time - AFK included, on",
            "purpose: this is a measure of presence, not of effort, and it is the reason play time",
            "is not an aura source.",
            "",
            "Exactly thirteen entries, the first of which is 0, rising strictly. Thirteen because",
            "that is how many crest designs the resource pack draws; a fourteenth tier would have",
            "nothing to render as. The tier is DERIVED and never stored, so retuning this list is",
            "a config edit rather than a migration plus a backfill.",
            "",
            "Calibrated so tier 13 is reachable in two to three months by somebody who plays",
            "regularly and leaves the client running some nights."
    })
    default List<Integer> prestigeThresholdHours() {
        return List.of(0, 2, 5, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);
    }

    // ---------------------------------------------------------------- the hunger games winner

    @Order(34)
    @Key("hg-winner-aura")
    @Comment({
            "The head start the start event's winner carries into the season, paid on their FIRST",
            "JOIN and never again. docs/smp.md#still-open lists the amount as open; PROPOSED HERE.",
            "",
            "150 is chosen against the season's own scale rather than out of the air: a top",
            "contributor finishes the whole track on roughly 350, so this is a visible head start",
            "on the leaderboard that a week of real contribution overtakes. Aura buys nothing, so",
            "the entire prize is recognition - which is also why it must not be so large that",
            "nobody can catch it."
    })
    default int hgWinnerAura() {
        return 150;
    }

    @Order(35)
    @Key("hg-winner-items")
    @Comment({
            "One or two special items for the winner, also PROPOSED rather than decided. Bukkit",
            "material names with an amount.",
            "",
            "An elytra and a netherite ingot: one of them is the thing everybody wants and nobody",
            "has on day one, the other is a head start on gear that is spent the moment it is used.",
            "Neither breaks anything - there are no claims to defend and no economy to inflate."
    })
    default List<WheelPrizeSpec> hgWinnerItems() {
        return DefaultSmp.HG_WINNER_ITEMS;
    }

    // ---------------------------------------------------------------- the wheel

    @Order(36)
    @Key("wheel-extra-spin-percents")
    @Comment({
            "The contribution shares that earn extra spins when an objective completes: one spin at",
            "the first, two at the second, three at the third. Hung off the same 2 % threshold the",
            "aura share uses, so there is one rule to understand and one place to change it."
    })
    default List<Integer> wheelExtraSpinPercents() {
        return List.of(2, 10, 25);
    }

    @Order(37)
    @Key("wheel-prizes")
    @Comment({
            "The wheel's pool and its weights - open in docs/smp.md#still-open, PROPOSED here.",
            "Weights are relative and need not sum to anything.",
            "",
            "Three bands, and the reasoning behind the third is the one that matters: COMMON is",
            "useful and never decisive, UNCOMMON is pleasant and still ordinary, and RARE is",
            "'things you occasionally need and hate farming'. The rare band is chosen to ENCOURAGE",
            "TRADE - everybody eventually holds something good they do not need and needs something",
            "they did not draw.",
            "",
            "The wheel is the only reward channel in the design that pays out actual items, so it",
            "is the one worth abusing; the weights below make the rare band roughly one spin in",
            "twenty-five."
    })
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }

    /** One prize, or one item of the winner's head start. */
    @ConfigSpec
    interface WheelPrizeSpec {

        @Order(1) @Key("item")
        @Comment("A Bukkit material name.")
        default String item() {
            return "";
        }

        @Order(2) @Key("amount")
        @Comment("How many.")
        default int amount() {
            return 1;
        }

        @Order(3) @Key("weight")
        @Comment("Relative weight. Ignored for the winner's head start, which is not drawn.")
        default int weight() {
            return 1;
        }
    }

    // ---------------------------------------------------------------- duels

    @Order(38)
    @Key("duel-loadout-sword")
    @Comment({
            "What both players are given inside a sword duel - open in docs/smp.md#still-open,",
            "PROPOSED here. Identical for both, from config: nobody wins by being richer.",
            "",
            "Iron rather than diamond, and no enchantments: the fight should be decided by aim and",
            "timing over about a minute, not by who lands the first critical. Sixteen golden",
            "apples' worth of healing is deliberately absent - a duel is a single fight, not a war",
            "of attrition. The player's real inventory is untouched; this is the arena's own."
    })
    default List<WheelPrizeSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    @Order(39)
    @Key("duel-loadout-bow")
    @Comment({
            "The bow duel's loadout. Also PROPOSED.",
            "",
            "A plain bow, sixty-four arrows and lighter armour than the sword loadout, so that a",
            "hit matters and a miss costs. No crossbow: the reload time turns the fight into a game",
            "of cover, which is not what a 3x3 platform and a small arena are for."
    })
    default List<WheelPrizeSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }

    // ---------------------------------------------------------------- sound
    //
    // Not here. What each feedback category sounds like lives in sounds.yml, described by
    // SoundsSpec, because it is the one config in this module an operator iterates on by ear with
    // players online - and config.yml is deliberately not reloadable. It was a block here for the
    // length of one afternoon on 2026-09-04; SoundsSpec's javadoc carries the reasoning for the
    // move.

    // ---------------------------------------------------------------- admin

    // `admin-permissions` WAS HERE, and is retired as of 2026-09-04. It listed the Bukkit
    // permission nodes attached to an admin at join. A list cannot answer "an admin must reliably
    // have every permission", because a list only knows what somebody wrote down: every plugin
    // added later brings nodes nobody adds to it. An admin is a server operator now - see
    // eu.nordtal.s2.common.access.AdminOperators, which also explains why ops.json is swept at
    // every enable.
    //
    // The key is deliberately NOT re-declared as a deprecated no-op: jcore stops a load on a key
    // the interface does not declare, which is what makes a stale `plugins/smp/config.yml` in a
    // deployed volume fail loudly and by name instead of silently doing nothing. ConfigsTest
    // asserts that refusal, and the owner's checklist carries the one-line edit on the host.
}
