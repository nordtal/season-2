package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.s2.settings.Refers;
import java.util.List;

/** The {@code config} group: everything about the SMP that is neither the track nor a database credential. */
@ConfigSpec
public interface SmpSpec {

    @Order(1)
    @Name("Nordtal world")
    @Key("world-nordtal")
    @Comment({
        "The permanent build world: the spawn, the tavern, the balloon, the duel platforms.",
        "Nothing is pre-generated; a milestone unlock only moves the border."
    })
    @Explain("The permanent world's folder name. Nothing is pre-generated; a milestone unlock only moves the border.")
    default String worldNordtal() {
        return "nordtal";
    }

    @Order(3)
    @Name("Nether world")
    @Key("world-nether")
    @Comment("The Nether. Fixed border, generated once before its own milestone unlocks.")
    @NoExplanationNeeded
    default String worldNether() {
        return "nordtal_nether";
    }

    @Order(4)
    @Name("End world")
    @Key("world-end")
    @Comment("The End. Entered by balloon only; a stronghold's End portal never activates.")
    @NoExplanationNeeded
    default String worldEnd() {
        return "nordtal_the_end";
    }

    @Order(6)
    @Name("Nether border diameter")
    @Key("nether-border-diameter")
    @Comment({
        "Several times larger than the 1:8 mapping requires, which leaves room for a milestone",
        "appended above 4000. Minecraft links portals beyond a border anyway."
    })
    @Explain("Several times larger than the 1:8 mapping requires, leaving room for a milestone appended later.")
    default int netherBorderDiameter() {
        return 2000;
    }

    @Order(7)
    @Name("End border diameter")
    @Key("end-border-diameter")
    @Comment("The End's fixed border.")
    @NoExplanationNeeded
    default int endBorderDiameter() {
        return 2000;
    }

    @Order(8)
    @Name("Border centre X")
    @Key("border-centre-x")
    @Comment("The Nordtal border's centre.")
    @Explain("Every radius in this file is measured from this point, so moving it shifts all of them at once.")
    default int borderCentreX() {
        return 106;
    }

    @Order(9)
    @Name("Border centre Z")
    @Key("border-centre-z")
    @Comment("See border-centre-x.")
    @Explain("Every radius in this file is measured from this point, so moving it shifts all of them at once.")
    default int borderCentreZ() {
        return 88;
    }

    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @Comment({
        "How fast a milestone's border expansion travels. At the default the final",
        "1550-block edge takes 15 to 30 minutes to arrive, a ceremony rather than a hiccup."
    })
    @Explain("How fast a border expansion travels; at this default the final edge takes 15 to 30 minutes.")
    default double borderExpansionBlocksPerSecond() {
        return 1.5;
    }

    @Order(14)
    @Name("Required datapacks")
    @Key("required-datapacks")
    @Comment({
        "The world-generation datapacks that MUST be installed and enabled, checked at enable.",
        "Matched case-insensitively as a substring of the names Paper reports, so 'Terralith'",
        "matches 'file/Terralith_26.2_v2.6.4.zip'.",
        "",
        "THE PLUGIN ONLY CHECKS. deploy/minecraft/entrypoint.sh installs them, and they apply to",
        "every world on the server. A world generated without them stays vanilla terrain forever."
    })
    @Explain("Checked at enable, never installed by this plugin: a world generated without them stays vanilla.")
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }

    @Order(19)
    @Name("Balloons")
    @Key("balloons")
    @Comment({
        "Where the balloons stand. Stepping into one of these boxes opens the travel GUI.",
        "One box each in Nordtal and the Nether; the End has none and is left by its exit portal.",
        "",
        "NORDTAL'S BALLOON MUST STAND outside radius 10 and inside radius 21.5 of the border",
        "centre, so the opening border of 20 withholds travel and the expansion to 43 hands it",
        "over. Everything else social belongs inside radius 10. The plugin refuses to start otherwise.",
        "",
        "The coordinates below are placeholders."
    })
    @Explain(
            "Where the balloon boxes stand. Nordtal's must sit outside radius 10 and inside 21.5 of the border centre.")
    default List<BalloonSpec> balloons() {
        return DefaultSmp.BALLOONS;
    }

    @Order(20)
    @Name("Boards")
    @Key("boards")
    @Comment({
        "The two boards at the spawn: the current milestone, and the aura leaderboard.",
        "",
        "RENDERED PER PLAYER, IN THEIR OWN LANGUAGE. Each entry is an anchor: every viewer gets",
        "their own hidden Text Display there. That does not scale to a hundred players.",
        "",
        "kind is OBJECTIVE or AURA. Anything else stops the load.",
        "",
        "The coordinates are placeholders until the spawn is built."
    })
    @Explain("Rendered once PER VIEWER, in their own language. This does not scale to a hundred players.")
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }

    @Order(21)
    @Name("Duel platforms")
    @Key("duel-platforms")
    @Comment({
        "The two 3x3 platforms at the spawn. Two players standing on the same one at the same",
        "time are taken into an arena.",
        "",
        "type is SWORD or BOW and picks which loadout both fighters get. Anything else stops",
        "the load.",
        "",
        "The coordinates are placeholders until the spawn is built. Both platforms belong",
        "INSIDE radius 10 of the border centre."
    })
    @Explain("type picks the loadout both fighters get. Both platforms belong inside radius 10 of the border centre.")
    default List<DuelPlatformSpec> duelPlatforms() {
        return DefaultSmp.DUEL_PLATFORMS;
    }

    @Order(22)
    @Name("Duel arena base Y")
    @Key("duel-arena-base-y")
    @Comment({
        "The height of the lowest arena. Further concurrent duels stack above it.",
        "Keep it well above anything players build."
    })
    @Explain("Height of the lowest stacked arena, well above anything players build.")
    default int duelArenaBaseY() {
        return 200;
    }

    @Order(23)
    @Name("Duel arena spacing")
    @Key("duel-arena-spacing")
    @Comment("Vertical distance between stacked arenas. Has to exceed the arena's own height.")
    @Explain("Must exceed the arena's own height, or stacked arenas overlap.")
    default int duelArenaSpacing() {
        return 16;
    }

    @Order(24)
    @Name("Duel arena radius")
    @Key("duel-arena-radius")
    @Comment({
        "Half the arena's floor, in blocks: a radius of 7 is a 15x15 floor. Big enough that a",
        "bow duel is not a knife fight, small enough that neither fighter can simply run."
    })
    @Explain("Half the arena's floor: big enough for a bow duel, small enough that neither fighter can run.")
    default int duelArenaRadius() {
        return 7;
    }

    @Order(25)
    @Name("Wheel regions")
    @Key("wheel-regions")
    @Comment({
        "Where the wheel of fortune stands in the tavern. Right-clicking inside one of these",
        "boxes spins it: one free spin per calendar day, plus spins earned on objectives.",
        "IT COSTS NO AURA: aura is recognition, not currency.",
        "",
        "The coordinates are placeholders until the tavern is built."
    })
    @Explain("Boxes that spin the wheel on right-click. Spinning never costs aura: it is recognition, not currency.")
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    @Order(26)
    @Name("NPC")
    @Key("npc")
    @Comment({
        "The figure in the tavern. Click it to open the objective list and hand items in.",
        "It is a vanilla MANNEQUIN: no AI, never despawns, cannot be killed.",
        "",
        "skin-name is a Minecraft account whose skin it wears, resolved at start; empty is the default.",
        "",
        "The coordinates are placeholders until the tavern is built."
    })
    @Explain("The clickable figure in the tavern, a Mannequin. Leave skin-name empty for the default skin.")
    default NpcSpec npc() {
        return DefaultSmp.NPC;
    }

    @Order(27)
    @Name("Spawn regions")
    @Key("spawn-regions")
    @Comment({
        "The protected zones: no building, no breaking, no interaction with blocks you do not",
        "own, no explosions.",
        "",
        "Boxes are inclusive on both corners and are checked in order; the first one that",
        "contains a block wins. THE COORDINATES BELOW ARE PLACEHOLDERS."
    })
    @Explain("Protected boxes, inclusive on both corners, checked in order with the first match winning.")
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }

    @Order(28)
    @Name("Death penalty")
    @Key("death-penalty")
    @Comment({
        "What an ordinary death costs, as a POSITIVE number that is subtracted at the point of",
        "use. Anywhere except the duel arena."
    })
    @Explain("What an ordinary death costs, as a positive number subtracted at the point of use.")
    default int deathPenalty() {
        return 5;
    }

    @Order(29)
    @Name("Death penalty (listed causes)")
    @Key("death-penalty-listed")
    @Comment("What one of the causes below costs instead. Also a positive number.")
    @NoExplanationNeeded
    default int deathPenaltyListed() {
        return 20;
    }

    @Order(30)
    @Name("Listed death causes")
    @Key("death-causes-listed")
    @Comment({
        "The 'embarrassing' deaths: nobody else caused them and a moment of attention would have",
        "prevented them. Damage-type keys, matched case-insensitively, minecraft: optional.",
        "Dying in the End before the dragon falls stays ORDINARY: it is the only way home."
    })
    @Explain("The self-inflicted deaths that cost the higher penalty above. Mobs, the border and starvation are not.")
    @Refers(Refers.To.DAMAGE_TYPE)
    default List<String> deathCausesListed() {
        return List.of(
                "minecraft:lava",
                "minecraft:in_fire",
                "minecraft:on_fire",
                "minecraft:cactus",
                "minecraft:drown",
                "minecraft:in_wall",
                "minecraft:sweet_berry_bush",
                "minecraft:hot_floor",
                "minecraft:campfire",
                "minecraft:stalagmite");
    }

    @Order(31)
    @Name("Duel stake")
    @Key("duel-stake")
    @Comment({
        "What a duel moves. The winner takes exactly what the loser pays, and a death in the",
        "arena costs nothing beyond it."
    })
    @Explain("What a duel moves: the winner takes exactly what the loser pays.")
    default int duelStake() {
        return 10;
    }

    @Order(32)
    @Name("Concurrent duel limit")
    @Key("concurrent-duel-limit")
    @Comment("How many arenas may be stacked above the spawn at once. Beyond it, players queue.")
    @NoExplanationNeeded
    default int concurrentDuelLimit() {
        return 3;
    }

    @Order(33)
    @Name("Advancement awards")
    @Key("advancement-awards")
    @Comment({
        "The advancements that pay aura, once each per player. The loader refuses anything",
        "outside 2 to 10: above it, one advancement would outweigh a whole objective."
    })
    @Explain("Which advancements pay aura, once each per player. The loader refuses anything outside 2 to 10.")
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }

    @Order(37)
    @Name("Wheel extra spin chances (percent)")
    @Key("wheel-extra-spin-percents")
    @Comment({
        "The contribution shares that earn extra spins when an objective completes: one spin at",
        "the first, two at the second, three at the third. The first matches the aura share's 2 %."
    })
    @Explain("The contribution shares that earn 1, 2 or 3 extra wheel spins when an objective completes.")
    default List<Integer> wheelExtraSpinPercents() {
        return List.of(2, 10, 25);
    }

    @Order(38)
    @Name("Wheel prizes")
    @Key("wheel-prizes")
    @Comment({
        "The wheel's pool and its weights. Weights are relative and need not sum to anything.",
        "The rare band, about one spin in twenty-five, is meant to encourage trade."
    })
    @Explain("The wheel's pool and relative weights; the rare band is about one spin in twenty-five.")
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }

    @Order(39)
    @Name("Duel loadout: sword")
    @Key("duel-loadout-sword")
    @Comment({
        "What both players are given inside a sword duel, identical for both. The player's real",
        "inventory is untouched."
    })
    @Explain("What both duelists are given, identical for both. No enchantments and no healing on purpose.")
    default List<WheelPrizeSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    @Order(40)
    @Name("Duel loadout: bow")
    @Key("duel-loadout-bow")
    @Comment({"The bow duel's loadout. No crossbow: its reload time turns the fight into a game of cover."})
    @Explain("The bow duel's loadout. No crossbow on purpose: its reload time turns the fight into cover.")
    default List<WheelPrizeSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }

    // Feedback sounds live in the reloadable the sounds group; see SoundsSpec.

    // jcore deletes the retired admin-permissions key rather than leaving a no-op.

    @Order(43)
    @Name("Balloon spawn points")
    @Key("balloon-spawn-points")
    @Comment({
        "Where the balloon PUTS A PLAYER DOWN, one point per world it flies to. Not the boxes",
        "above, not the vanilla world spawn, and not the first join. Moving one moves nothing else.",
        "",
        "A point nobody fits at is searched outwards from its column; if nothing is found the",
        "balloon REFUSES the trip and says so. The key a point sits under names its world.",
        "",
        "THE COORDINATES BELOW ARE PLACEHOLDERS."
    })
    @Explain("Where the balloon sets a player down, one point per destination world. Not the boxes above.")
    default BalloonSpawnPointsSpec balloonSpawnPoints() {
        return DefaultSmp.BALLOON_SPAWN_POINTS;
    }

    @Order(44)
    @Name("First join spawn")
    @Key("first-join-spawn")
    @Comment({
        "Where a player is put down on their VERY FIRST JOIN of the season, and nowhere else.",
        "USED EXACTLY ONCE PER PLAYER, claimed with smp_player.welcome_shown; a player with no",
        "linked Discord account is not moved at all.",
        "",
        "This one names its world, which has to exist; the plugin warns at start if it does not.",
        "A point nobody fits at is used as written rather than cancelling the arrival.",
        "",
        "THE COORDINATES BELOW ARE PLACEHOLDERS."
    })
    @Explain("Where a player lands on their very first join only, claimed once against smp_player.welcome_shown.")
    default FirstJoinSpawnSpec firstJoinSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    @Order(45)
    @Name("Grave max age (hours)")
    @Key("grave-max-age-hours")
    @Comment({
        "How long a grave stands before it decays, in hours. THE CONTENTS GO WITH IT, as with",
        "vanilla items that despawn. 0 turns decay off; negative is refused at load.",
        "",
        "The clock is the row's `created`, so lowering this expires graves that already stand,",
        "and raising it brings nothing back."
    })
    @Explain("How long a grave stands before it and everything in it decays, in hours. 0 never decays.")
    default int graveMaxAgeHours() {
        return 24;
    }
}
