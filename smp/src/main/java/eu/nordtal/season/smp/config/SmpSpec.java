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

    /** Nothing is pre-generated; a milestone unlock only moves the border. */
    @Order(1)
    @Name("Nordtal world")
    @Key("world-nordtal")
    @NoExplanationNeeded
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

    /** Several times larger than the 1:8 mapping requires, leaving room for a milestone appended later. */
    @Order(6)
    @Name("Nether border diameter")
    @Key("nether-border-diameter")
    @NoExplanationNeeded
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

    /** Every radius is measured from here; moving it shifts them all. */
    @Order(8)
    @Name("Border centre X")
    @Key("border-centre-x")
    @NoExplanationNeeded
    default int borderCentreX() {
        return 106;
    }

    /** Every radius is measured from here; moving it shifts them all. */
    @Order(9)
    @Name("Border centre Z")
    @Key("border-centre-z")
    @NoExplanationNeeded
    default int borderCentreZ() {
        return 88;
    }

    /** At the default the final edge takes 15 to 30 minutes. */
    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @NoExplanationNeeded
    default double borderExpansionBlocksPerSecond() {
        return 1.5;
    }

    /** Checked at enable. */
    @Order(14)
    @Name("Required datapacks")
    @Key("required-datapacks")
    @Explain("Start-up fails if these data packs are not installed.")
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }

    /** Where the balloon boxes stand. */
    @Order(19)
    @Name("Balloons")
    @Key("balloons")
    @NoExplanationNeeded
    default List<BalloonSpec> balloons() {
        return DefaultSmp.BALLOONS;
    }

    /** Rendered per viewer; does not scale to a hundred players. */
    @Order(20)
    @Name("Boards")
    @Key("boards")
    @NoExplanationNeeded
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }

    /** type picks the loadout both fighters get. */
    @Order(21)
    @Name("Duel platforms")
    @Key("duel-platforms")
    @NoExplanationNeeded
    default List<DuelPlatformSpec> duelPlatforms() {
        return DefaultSmp.DUEL_PLATFORMS;
    }

    /** Height of the lowest stacked arena, well above anything players build. */
    @Order(22)
    @Name("Duel arena base Y")
    @Key("duel-arena-base-y")
    @NoExplanationNeeded
    default int duelArenaBaseY() {
        return 200;
    }

    /** Otherwise stacked arenas overlap. */
    @Order(23)
    @Name("Duel arena spacing")
    @Key("duel-arena-spacing")
    @Explain("Must exceed the arena's own height, or stacked arenas overlap.")
    default int duelArenaSpacing() {
        return 16;
    }

    /** Half the arena's floor: big enough for a bow duel, small enough that neither fighter can run. */
    @Order(24)
    @Name("Duel arena radius")
    @Key("duel-arena-radius")
    @NoExplanationNeeded
    default int duelArenaRadius() {
        return 7;
    }

    /** Boxes that spin the wheel on right-click; spinning never costs aura, it is recognition, not currency. */
    @Order(25)
    @Name("Wheel regions")
    @Key("wheel-regions")
    @NoExplanationNeeded
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    /** The clickable figure in the tavern, a Mannequin. */
    @Order(26)
    @Name("NPC")
    @Key("npc")
    @NoExplanationNeeded
    default NpcSpec npc() {
        return DefaultSmp.NPC;
    }

    /** Inclusive on both corners; the first match wins. */
    @Order(27)
    @Name("Spawn regions")
    @Key("spawn-regions")
    @NoExplanationNeeded
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }

    /** Subtracted at the point of use. */
    @Order(28)
    @Name("Death penalty")
    @Key("death-penalty")
    @NoExplanationNeeded
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

    /** Self-inflicted deaths that cost the higher penalty. Mobs, the border and starvation do not. */
    @Order(30)
    @Name("Listed death causes")
    @Key("death-causes-listed")
    @NoExplanationNeeded
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

    /** The winner takes exactly what the loser pays. */
    @Order(31)
    @Name("Duel stake")
    @Key("duel-stake")
    @NoExplanationNeeded
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

    /** Pays once per player. Anything outside 2 to 10 is refused. */
    @Order(33)
    @Name("Advancement awards")
    @Key("advancement-awards")
    @NoExplanationNeeded
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }

    /** The rare band is about one spin in twenty-five. */
    @Order(37)
    @Name("Wheel prizes")
    @Key("wheel-prizes")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }

    /** What both duelists are given, identical for both; no enchantments and no healing on purpose. */
    @Order(38)
    @Name("Duel loadout: sword")
    @Key("duel-loadout-sword")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    /** The bow duel's loadout; no crossbow on purpose, because its reload time turns the fight into cover. */
    @Order(39)
    @Name("Duel loadout: bow")
    @Key("duel-loadout-bow")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }

    // Feedback sounds live in the reloadable the sounds group; see SoundsSpec.

    /** Not the boxes above. */
    @Order(42)
    @Name("Balloon spawn points")
    @Key("balloon-spawn-points")
    @NoExplanationNeeded
    default BalloonSpawnPointsSpec balloonSpawnPoints() {
        return DefaultSmp.BALLOON_SPAWN_POINTS;
    }

    /** Claimed once against smp_player.welcome_shown. */
    @Order(43)
    @Name("First join spawn")
    @Key("first-join-spawn")
    @NoExplanationNeeded
    default FirstJoinSpawnSpec firstJoinSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    /** How long a grave stands before it and everything in it decays. */
    @Order(44)
    @Name("Grave max age (hours)")
    @Key("grave-max-age-hours")
    @NoExplanationNeeded
    default int graveMaxAgeHours() {
        return 24;
    }
}
