package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code config} group: everything about the SMP that is neither the track nor a database credential. */
@ConfigSpec
public interface SmpSpec {

    @Order(1)
    @Name("Nordtal world")
    @Key("world-nordtal")
    @Explain("The permanent world's folder name. Nothing is pre-generated; a milestone unlock only moves the border.")
    default String worldNordtal() {
        return "nordtal";
    }

    @Order(3)
    @Name("Nether world")
    @Key("world-nether")
    @NoExplanationNeeded
    default String worldNether() {
        return "nordtal_nether";
    }

    @Order(4)
    @Name("End world")
    @Key("world-end")
    @NoExplanationNeeded
    default String worldEnd() {
        return "nordtal_the_end";
    }

    @Order(6)
    @Name("Nether border diameter")
    @Key("nether-border-diameter")
    @Explain("Several times larger than the 1:8 mapping requires, leaving room for a milestone appended later.")
    default int netherBorderDiameter() {
        return 2000;
    }

    @Order(7)
    @Name("End border diameter")
    @Key("end-border-diameter")
    @NoExplanationNeeded
    default int endBorderDiameter() {
        return 2000;
    }

    @Order(8)
    @Name("Border centre X")
    @Key("border-centre-x")
    @Explain("Every radius in this file is measured from this point, so moving it shifts all of them at once.")
    default int borderCentreX() {
        return 106;
    }

    @Order(9)
    @Name("Border centre Z")
    @Key("border-centre-z")
    @Explain("Every radius in this file is measured from this point, so moving it shifts all of them at once.")
    default int borderCentreZ() {
        return 88;
    }

    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @Explain("How fast a border expansion travels; at this default the final edge takes 15 to 30 minutes.")
    default double borderExpansionBlocksPerSecond() {
        return 1.5;
    }

    @Order(14)
    @Name("Required datapacks")
    @Key("required-datapacks")
    @Explain("Checked at enable, never installed by this plugin: a world generated without them stays vanilla.")
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }

    @Order(19)
    @Name("Balloons")
    @Key("balloons")
    @Explain(
            "Where the balloon boxes stand. Nordtal's must sit outside radius 10 and inside 21.5 of the border centre.")
    default List<BalloonSpec> balloons() {
        return DefaultSmp.BALLOONS;
    }

    @Order(20)
    @Name("Boards")
    @Key("boards")
    @Explain("Rendered once PER VIEWER, in their own language. This does not scale to a hundred players.")
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }

    @Order(21)
    @Name("Duel platforms")
    @Key("duel-platforms")
    @Explain("type picks the loadout both fighters get. Both platforms belong inside radius 10 of the border centre.")
    default List<DuelPlatformSpec> duelPlatforms() {
        return DefaultSmp.DUEL_PLATFORMS;
    }

    @Order(22)
    @Name("Duel arena base Y")
    @Key("duel-arena-base-y")
    @Explain("Height of the lowest stacked arena, well above anything players build.")
    default int duelArenaBaseY() {
        return 200;
    }

    @Order(23)
    @Name("Duel arena spacing")
    @Key("duel-arena-spacing")
    @Explain("Must exceed the arena's own height, or stacked arenas overlap.")
    default int duelArenaSpacing() {
        return 16;
    }

    @Order(24)
    @Name("Duel arena radius")
    @Key("duel-arena-radius")
    @Explain("Half the arena's floor: big enough for a bow duel, small enough that neither fighter can run.")
    default int duelArenaRadius() {
        return 7;
    }

    @Order(25)
    @Name("Wheel regions")
    @Key("wheel-regions")
    @Explain("Boxes that spin the wheel on right-click. Spinning never costs aura: it is recognition, not currency.")
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    @Order(26)
    @Name("NPC")
    @Key("npc")
    @Explain("The clickable figure in the tavern, a Mannequin. Leave skin-name empty for the default skin.")
    default NpcSpec npc() {
        return DefaultSmp.NPC;
    }

    @Order(27)
    @Name("Spawn regions")
    @Key("spawn-regions")
    @Explain("Protected boxes, inclusive on both corners, checked in order with the first match winning.")
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }

    @Order(28)
    @Name("Death penalty")
    @Key("death-penalty")
    @Explain("What an ordinary death costs, as a positive number subtracted at the point of use.")
    default int deathPenalty() {
        return 5;
    }

    @Order(29)
    @Name("Death penalty (listed causes)")
    @Key("death-penalty-listed")
    @NoExplanationNeeded
    default int deathPenaltyListed() {
        return 20;
    }

    @Order(30)
    @Name("Listed death causes")
    @Key("death-causes-listed")
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
    @Explain("What a duel moves: the winner takes exactly what the loser pays.")
    default int duelStake() {
        return 10;
    }

    @Order(32)
    @Name("Concurrent duel limit")
    @Key("concurrent-duel-limit")
    @NoExplanationNeeded
    default int concurrentDuelLimit() {
        return 3;
    }

    @Order(33)
    @Name("Advancement awards")
    @Key("advancement-awards")
    @Explain("Which advancements pay aura, once each per player. The loader refuses anything outside 2 to 10.")
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }

    @Order(37)
    @Name("Wheel prizes")
    @Key("wheel-prizes")
    @Explain("The wheel's pool and relative weights; the rare band is about one spin in twenty-five.")
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }

    @Order(38)
    @Name("Duel loadout: sword")
    @Key("duel-loadout-sword")
    @Explain("What both duelists are given, identical for both. No enchantments and no healing on purpose.")
    default List<WheelPrizeSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    @Order(39)
    @Name("Duel loadout: bow")
    @Key("duel-loadout-bow")
    @Explain("The bow duel's loadout. No crossbow on purpose: its reload time turns the fight into cover.")
    default List<WheelPrizeSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }

    // Feedback sounds live in the reloadable the sounds group; see SoundsSpec.

    @Order(42)
    @Name("Balloon spawn points")
    @Key("balloon-spawn-points")
    @Explain("Where the balloon sets a player down, one point per destination world. Not the boxes above.")
    default BalloonSpawnPointsSpec balloonSpawnPoints() {
        return DefaultSmp.BALLOON_SPAWN_POINTS;
    }

    @Order(43)
    @Name("First join spawn")
    @Key("first-join-spawn")
    @Explain("Where a player lands on their very first join only, claimed once against smp_player.welcome_shown.")
    default FirstJoinSpawnSpec firstJoinSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    @Order(44)
    @Name("Grave max age (hours)")
    @Key("grave-max-age-hours")
    @Explain("How long a grave stands before it and everything in it decays, in hours. 0 never decays.")
    default int graveMaxAgeHours() {
        return 24;
    }
}
