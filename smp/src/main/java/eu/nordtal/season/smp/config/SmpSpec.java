package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/** The {@code config} group holds every SMP setting except the track and the database credentials. */
@ConfigSpec
public interface SmpSpec {

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

    @Order(8)
    @Name("Border centre X")
    @Key("border-centre-x")
    @NoExplanationNeeded
    default int borderCentreX() {
        return 106;
    }

    @Order(9)
    @Name("Border centre Z")
    @Key("border-centre-z")
    @NoExplanationNeeded
    default int borderCentreZ() {
        return 88;
    }

    @Order(10)
    @Name("Border expansion speed (blocks per second)")
    @Key("border-expansion-blocks-per-second")
    @NoExplanationNeeded
    default double borderExpansionBlocksPerSecond() {
        return 1.5;
    }

    @Order(14)
    @Name("Required datapacks")
    @Key("required-datapacks")
    @Explain("Start-up fails if these data packs are not installed.")
    default List<String> requiredDatapacks() {
        return List.of("Terralith", "Dungeons and Taverns");
    }

    @Order(19)
    @Name("Balloons")
    @Key("balloons")
    @NoExplanationNeeded
    default List<BalloonSpec> balloons() {
        return DefaultSmp.BALLOONS;
    }

    @Order(20)
    @Name("Boards")
    @Key("boards")
    @NoExplanationNeeded
    default List<BoardSpec> boards() {
        return DefaultSmp.BOARDS;
    }

    /** The {@code type} of a duel platform selects the loadout of both fighters. */
    @Order(21)
    @Name("Duel platforms")
    @Key("duel-platforms")
    @NoExplanationNeeded
    default List<DuelPlatformSpec> duelPlatforms() {
        return DefaultSmp.DUEL_PLATFORMS;
    }

    @Order(22)
    @Name("Duel arena base Y")
    @Key("duel-arena-base-y")
    @NoExplanationNeeded
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
    @NoExplanationNeeded
    default int duelArenaRadius() {
        return 7;
    }

    /** A spin costs no aura. */
    @Order(25)
    @Name("Wheel regions")
    @Key("wheel-regions")
    @NoExplanationNeeded
    default List<SpawnRegionSpec> wheelRegions() {
        return DefaultSmp.WHEEL_REGIONS;
    }

    /** The NPC is a Mannequin. */
    @Order(26)
    @Name("NPC")
    @Key("npc")
    @NoExplanationNeeded
    default NpcSpec npc() {
        return DefaultSmp.NPC;
    }

    /** Both corners of a region are inclusive. The first matching region applies. */
    @Order(27)
    @Name("Spawn regions")
    @Key("spawn-regions")
    @NoExplanationNeeded
    default List<SpawnRegionSpec> spawnRegions() {
        return DefaultSmp.SPAWN_REGIONS;
    }

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

    /** Mobs, the border and starvation do not cost the listed death penalty. */
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

    /** Each award pays once per player. An aura outside 2 to 10 is refused. */
    @Order(33)
    @Name("Advancement awards")
    @Key("advancement-awards")
    @NoExplanationNeeded
    default List<AdvancementAwardSpec> advancementAwards() {
        return DefaultSmp.ADVANCEMENT_AWARDS;
    }

    @Order(37)
    @Name("Wheel prizes")
    @Key("wheel-prizes")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> wheelPrizes() {
        return DefaultSmp.WHEEL_PRIZES;
    }

    @Order(38)
    @Name("Duel loadout: sword")
    @Key("duel-loadout-sword")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    @Order(39)
    @Name("Duel loadout: bow")
    @Key("duel-loadout-bow")
    @NoExplanationNeeded
    default List<WheelPrizeSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }

    @Order(42)
    @Name("Balloon spawn points")
    @Key("balloon-spawn-points")
    @NoExplanationNeeded
    default BalloonSpawnPointsSpec balloonSpawnPoints() {
        return DefaultSmp.BALLOON_SPAWN_POINTS;
    }

    /** {@code smp_player.welcome_shown} records the first join. */
    @Order(43)
    @Name("First join spawn")
    @Key("first-join-spawn")
    @NoExplanationNeeded
    default FirstJoinSpawnSpec firstJoinSpawn() {
        return DefaultSmp.FIRST_JOIN_SPAWN;
    }

    @Order(44)
    @Name("Grave max age (hours)")
    @Key("grave-max-age-hours")
    @NoExplanationNeeded
    default int graveMaxAgeHours() {
        return 24;
    }
}
