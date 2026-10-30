package eu.nordtal.season.smp.config;

import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import java.util.List;

/**
 * The {@code duels} group holds the negotiation area, the one arena, the fighters' positions and the kits.
 * A running duel keeps the arena and kit it started with.
 */
@ConfigSpec
public interface DuelsSpec {

    @Order(21)
    @Name("Negotiation area")
    @Key("negotiation-area")
    @Explain("Two players standing here agree on a kit. Nobody else may step on while they do or while they fight.")
    default DuelAreaSpec negotiationArea() {
        return DefaultSmp.DUEL_NEGOTIATION_AREA;
    }

    @Order(22)
    @Name("Arena")
    @Key("arena")
    @Explain("Saved before each duel and restored after it. Fighters may break and place blocks inside it.")
    default DuelAreaSpec arena() {
        return DefaultSmp.DUEL_ARENA;
    }

    @Order(23)
    @Name("First fighter's start")
    @Key("fighter-start-first")
    @NoExplanationNeeded
    default DuelPositionSpec fighterStartFirst() {
        return DefaultSmp.DUEL_START_FIRST;
    }

    @Order(24)
    @Name("Second fighter's start")
    @Key("fighter-start-second")
    @NoExplanationNeeded
    default DuelPositionSpec fighterStartSecond() {
        return DefaultSmp.DUEL_START_SECOND;
    }

    @Order(25)
    @Name("Exit")
    @Key("exit")
    @Explain("Where both fighters stand after the duel. Put it beside the negotiation area, not on it.")
    default DuelPositionSpec exit() {
        return DefaultSmp.DUEL_EXIT;
    }

    @Order(31)
    @Name("Duel stake")
    @Key("duel-stake")
    @NoExplanationNeeded
    default int duelStake() {
        return 10;
    }

    @Order(38)
    @Name("Duel kits")
    @Key("duel-kits")
    @Explain("One to five kits: the negotiation window has a row for each.")
    default List<DuelKitSpec> duelKits() {
        return DefaultSmp.DUEL_KITS;
    }
}
