package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/**
 * The {@code duels} group holds the platforms, the arena and the loadouts.
 * A running duel keeps the arena and loadout it started with.
 */
@ConfigSpec
public interface DuelsSpec {

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

    @Order(38)
    @Name("Duel loadout: sword")
    @Key("duel-loadout-sword")
    @NoExplanationNeeded
    default List<DuelLoadoutItemSpec> duelLoadoutSword() {
        return DefaultSmp.DUEL_LOADOUT_SWORD;
    }

    @Order(39)
    @Name("Duel loadout: bow")
    @Key("duel-loadout-bow")
    @NoExplanationNeeded
    default List<DuelLoadoutItemSpec> duelLoadoutBow() {
        return DefaultSmp.DUEL_LOADOUT_BOW;
    }
}
