package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every invariant {@link Declaration} actually enforces, and the one question it answers.
 *
 * <p>These are all the shapes that would otherwise fail late: a greedy argument in the wrong place
 * parses and never works, a required argument behind an optional one describes a command nobody can
 * type, and two arguments with one name lose the first one silently. None of them is visible from a
 * command's own source, which is why the check is in the type every command has to build.</p>
 */
class DeclarationTest {

    private static Declaration of(final List<Argument> arguments) {
        return new Declaration(List.of("smp", "aura"), Target.SMP, Set.of(Surface.GAME),
                true, false, arguments);
    }

    @Test
    @DisplayName("a greedy argument has to be last")
    void greedyMustBeLast() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> of(List.of(Argument.greedy("when"), Argument.word("key"))));

        assertTrue(refused.getMessage().contains("when"), refused.getMessage());
    }

    @Test
    @DisplayName("a greedy argument in last place is fine - /phase launch is exactly this")
    void greedyLastIsFine() {
        final Declaration launch = new Declaration(List.of("phase", "launch"), Target.PROXY,
                Set.of(Surface.GAME, Surface.DISCORD), true, false,
                List.of(Argument.greedy("when")));

        assertEquals("/phase launch", launch.name());
    }

    @Test
    @DisplayName("a required argument cannot follow an optional one")
    void requiredCannotFollowOptional() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> of(List.of(Argument.word("key").optional(), Argument.word("other"))));

        assertTrue(refused.getMessage().contains("other"), refused.getMessage());
    }

    @Test
    @DisplayName("an optional argument after a required one is the ordinary case")
    void optionalAfterRequiredIsFine() {
        assertEquals(2, of(List.of(Argument.word("key"), Argument.word("note").optional()))
                .arguments().size());
    }

    @Test
    @DisplayName("two arguments cannot share a name")
    void namesAreUnique() {
        assertThrows(IllegalArgumentException.class,
                () -> of(List.of(Argument.word("key"), Argument.integer("key", 0, 1))));
    }

    @Test
    @DisplayName("a command on no surface is refused, because nothing would register it")
    void everyCommandNeedsASurface() {
        assertThrows(IllegalArgumentException.class,
                () -> new Declaration(List.of("smp"), Target.SMP, Set.of(), true, false, List.of()));
    }

    @Test
    @DisplayName("a path segment cannot be blank")
    void pathSegmentsAreReal() {
        assertThrows(IllegalArgumentException.class,
                () -> new Declaration(List.of("smp", " "), Target.SMP, Set.of(Surface.GAME),
                        true, false, List.of()));
    }

    @Test
    @DisplayName("remote is decided by the asking process, not by the surface")
    void remoteFollowsTheHost() {
        // The case that made the earlier signature wrong: Surface.GAME is four different processes,
        // so asking by surface answered "local" for /hg start typed on the SMP.
        final Declaration start = new Declaration(List.of("hg", "start"), Target.HUNGER_GAMES,
                Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE), true, false, List.of());

        assertFalse(start.isRemoteOn(Target.HUNGER_GAMES));
        assertTrue(start.isRemoteOn(Target.SMP));
        assertTrue(start.isRemoteOn(Target.BOT));
    }

    @Test
    @DisplayName("the name is the path, and it is the same string on every surface")
    void nameIsThePath() {
        assertEquals("/smp aura", of(List.of()).name());
    }

    @Test
    @DisplayName("an integer argument with min above max is refused")
    void integerBoundsMakeSense() {
        assertThrows(IllegalArgumentException.class, () -> Argument.integer("delta", 10, -10));
    }

    @Test
    @DisplayName("a CHOICE with nothing to choose from is refused")
    void choicesAreNotEmpty() {
        assertThrows(IllegalArgumentException.class, () -> Argument.choice("phase", List.of()));
    }

    @Test
    @DisplayName("a non-CHOICE carrying choices is refused, because nothing would apply them")
    void onlyChoicesCarryChoices() {
        assertThrows(IllegalArgumentException.class,
                () -> new Argument("key", Argument.Kind.WORD, true, 0, 0, List.of("a", "b")));
    }
}
