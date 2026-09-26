package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every invariant {@link Declaration} actually enforces, and the one question it answers.
 *
 * These are all the shapes that would otherwise fail late: a greedy argument in the wrong place
 * parses and never works, a required argument behind an optional one describes a command nobody can
 * type, and two arguments with one name lose the first one silently. None of them is visible from a
 * command's own source, which is why the check is in the type every command has to build.
 */
class DeclarationTest {

    private static Declaration of(final List<Argument> arguments) {
        return new Declaration(List.of("smp", "aura"), Target.SMP, Set.of(Surface.GAME), true, false, arguments);
    }

    @Test
    void aGreedyArgumentHasToBeLast() {
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> of(List.of(Argument.greedy("when"), Argument.word("key"))));

        assertTrue(refused.getMessage().contains("when"), refused.getMessage());
    }

    @Test
    void aGreedyArgumentInLastPlaceIsFinePhaseLaunchIsExactlyThis() {
        final Declaration launch = new Declaration(
                List.of("phase", "launch"),
                Target.PROXY,
                Set.of(Surface.GAME, Surface.DISCORD),
                true,
                false,
                List.of(Argument.greedy("when")));

        assertEquals("/phase launch", launch.name());
    }

    @Test
    void aRequiredArgumentCannotFollowAnOptionalOne() {
        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> of(List.of(Argument.word("key").optional(), Argument.word("other"))));

        assertTrue(refused.getMessage().contains("other"), refused.getMessage());
    }

    @Test
    void anOptionalArgumentAfterARequiredOneIsTheOrdinaryCase() {
        assertEquals(
                2,
                of(List.of(Argument.word("key"), Argument.word("note").optional()))
                        .arguments()
                        .size());
    }

    @Test
    void twoArgumentsCannotShareAName() {
        assertThrows(
                IllegalArgumentException.class, () -> of(List.of(Argument.word("key"), Argument.integer("key", 0, 1))));
    }

    @Test
    void aCommandOnNoSurfaceIsRefusedBecauseNothingWouldRegisterIt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Declaration(List.of("smp"), Target.SMP, Set.of(), true, false, List.of()));
    }

    @Test
    void aPathSegmentCannotBeBlank() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Declaration(List.of("smp", " "), Target.SMP, Set.of(Surface.GAME), true, false, List.of()));
    }

    @Test
    void remoteIsDecidedByTheAskingProcessNotByTheSurface() {
        // The case that made the earlier signature wrong: Surface.GAME is four different processes.
        final Declaration start = new Declaration(
                List.of("hg", "start"),
                Target.HUNGER_GAMES,
                Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE),
                true,
                false,
                List.of());

        assertFalse(start.isRemoteOn(Target.HUNGER_GAMES));
        assertTrue(start.isRemoteOn(Target.SMP));
        assertTrue(start.isRemoteOn(Target.BOT));
    }

    @Test
    void theNameIsThePathAndItIsTheSameStringOnEverySurface() {
        assertEquals("/smp aura", of(List.of()).name());
    }

    @Test
    void anIntegerArgumentWithMinAboveMaxIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Argument.integer("delta", 10, -10));
    }

    @Test
    void aChoiceWithNothingToChooseFromIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Argument.choice("phase", List.of()));
    }

    @Test
    void aNonChoiceCarryingChoicesIsRefusedBecauseNothingWouldApplyThem() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Argument("key", Argument.Kind.WORD, true, 0, 0, List.of("a", "b")));
    }
}
