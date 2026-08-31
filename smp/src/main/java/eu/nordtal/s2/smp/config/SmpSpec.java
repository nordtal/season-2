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
            "see docs/operations.md#measuring-the-pre-generation--do-this-first."
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

    // ---------------------------------------------------------------- spawn protection

    @Order(13)
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

    @Order(14)
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

    @Order(15)
    @Key("death-penalty-listed")
    @Comment("What one of the causes below costs instead. Also a positive number.")
    default int deathPenaltyListed() {
        return 20;
    }

    @Order(16)
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

    @Order(17)
    @Key("duel-stake")
    @Comment({
            "What a duel moves. The winner takes exactly what the loser pays, so a duel never",
            "creates or destroys aura - and the arena is the one place a death costs nothing",
            "beyond it, because the stake has already settled the fight."
    })
    default int duelStake() {
        return 10;
    }

    @Order(18)
    @Key("concurrent-duel-limit")
    @Comment("How many arenas may be stacked above the spawn at once. Beyond it, players queue.")
    default int concurrentDuelLimit() {
        return 3;
    }

    @Order(19)
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

    @Order(20)
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

    @Order(21)
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

    @Order(22)
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

    @Order(23)
    @Key("wheel-extra-spin-percents")
    @Comment({
            "The contribution shares that earn extra spins when an objective completes: one spin at",
            "the first, two at the second, three at the third. Hung off the same 2 % threshold the",
            "aura share uses, so there is one rule to understand and one place to change it."
    })
    default List<Integer> wheelExtraSpinPercents() {
        return List.of(2, 10, 25);
    }

    @Order(24)
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

    @Order(25)
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

    @Order(26)
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

    // ---------------------------------------------------------------- admin

    @Order(27)
    @Key("admin-permissions")
    @Comment({
            "The Bukkit permission nodes attached to an admin at join and removed at quit, through",
            "a PermissionAttachment. NO LUCKPERMS: the Discord admin role is mirrored into the",
            "database by the bot, every process reads it with the query it makes anyway, and this",
            "is only for what goes through Bukkit permissions - vanilla commands and third-party",
            "plugins."
    })
    default List<String> adminPermissions() {
        return List.of("minecraft.command.gamemode", "minecraft.command.teleport",
                "minecraft.command.give", "minecraft.command.time", "minecraft.command.weather",
                "bukkit.command.plugins");
    }
}
