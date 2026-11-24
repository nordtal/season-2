package eu.nordtal.season.smp.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.settings.rule.Between;
import eu.nordtal.season.settings.rule.Longest;
import eu.nordtal.season.settings.rule.NotNegative;
import eu.nordtal.season.settings.rule.Required;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;

/** One door or trapdoor inside spawn that players may open, with its marker and optional auto-reset. */
@ConfigSpec
public interface SpawnExceptionSpec {

    /** Whether the entry is a two-block door or a single-block trapdoor. */
    enum Kind {
        DOOR,
        TRAPDOOR
    }

    /** The cardinal facing stored on the door or trapdoor block data. */
    enum Facing {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    /** Whether the door or trapdoor defaults to closed or open. */
    enum DefaultState {
        CLOSED,
        OPEN
    }

    /** Whether the block stays in its new state or resets automatically after a delay. */
    enum ResetMode {
        STAY,
        AUTO_RESET
    }

    /**
     * Which large face(s) show the right-click marker, oriented by the block's default state.
     * {@code FRONT} is North (N/S faces), East (E/W faces) or Top (flat trapdoor); {@code BACK} is the opposite.
     */
    enum IconSide {
        BOTH,
        FRONT,
        BACK,
        NONE
    }

    @Order(1)
    @Name("World")
    @Key("world")
    @Required
    @Refers(Refers.To.WORLD)
    @NoExplanationNeeded
    default String world() {
        return "";
    }

    @Order(2)
    @Name("X")
    @Key("x")
    @NoExplanationNeeded
    default int x() {
        return 0;
    }

    @Order(3)
    @Name("Y")
    @Key("y")
    @NoExplanationNeeded
    default int y() {
        return 0;
    }

    @Order(4)
    @Name("Z")
    @Key("z")
    @NoExplanationNeeded
    default int z() {
        return 0;
    }

    @Order(5)
    @Name("Label")
    @Key("label")
    @Longest(64)
    @NoExplanationNeeded
    default String label() {
        return "";
    }

    @Order(6)
    @Name("Kind")
    @Key("kind")
    @NoExplanationNeeded
    default Kind kind() {
        return Kind.DOOR;
    }

    @Order(7)
    @Name("Facing")
    @Key("facing")
    @NoExplanationNeeded
    default Facing facing() {
        return Facing.NORTH;
    }

    @Order(8)
    @Name("Default state")
    @Key("default-state")
    @NoExplanationNeeded
    default DefaultState defaultState() {
        return DefaultState.CLOSED;
    }

    @Order(9)
    @Name("Reset behaviour")
    @Key("reset-mode")
    @NoExplanationNeeded
    default ResetMode resetMode() {
        return ResetMode.STAY;
    }

    @Order(10)
    @Name("Reset minutes")
    @Key("reset-minutes")
    @NotNegative
    @NoExplanationNeeded
    default int resetMinutes() {
        return 0;
    }

    @Order(11)
    @Name("Reset seconds")
    @Key("reset-seconds")
    @Between(least = 0, most = 59)
    @NoExplanationNeeded
    default int resetSeconds() {
        return 0;
    }

    @Order(12)
    @Name("Icon side")
    @Key("icon-side")
    @NoExplanationNeeded
    default IconSide iconSide() {
        return IconSide.BOTH;
    }
}
