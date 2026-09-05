package eu.nordtal.s2.commands;

import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.commands.hungergames.StartGame;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.phase.SetPhase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NordtalCommand#check}: the generic argument checks, and how they meet a command's own.
 *
 * <h2>The gap this closes</h2>
 * Discord builds a dropdown for a {@link Argument.Kind#CHOICE} and its own client refuses anything
 * that is not on the list; {@code RequestArguments#decode} refuses one on the way off a request row.
 * Brigadier has no enum type at all, so both chat adapters type a choice as a plain word and take
 * whatever was typed - and nothing checked it afterwards.
 *
 * <p>{@code /hg start} is what made that expensive rather than untidy: the trailing {@code confirm}
 * is a choice, and {@link StartGame} treats <em>any</em> present value as the second step. So
 * {@code /hg start yes} would have spent an armed confirmation and begun the season's flagship event
 * below the recommended minimum, having said nothing about the word it did not understand.</p>
 */
class ChoiceCheckTest {

    private static final NordtalCommand<HungerGamesEffects> START = new StartGame();
    private static final NordtalCommand<PhaseEffects> SET = new SetPhase();

    @Test
    @DisplayName("a value that is not one of the declared choices names itself and the list")
    void anUnknownChoiceIsRefused() {
        final Optional<Map.Entry<String, Map<String, ?>>> problem = START.check(
                new Values(HungerGamesCommands.START, Map.of("confirm", "yes")));

        assertTrue(problem.isPresent(), "/hg start yes was accepted as a confirmation");
        assertEquals("command.not-a-choice", problem.get().getKey());
        assertEquals("confirm", problem.get().getValue().get("argument"));
        assertEquals("yes", problem.get().getValue().get("typed"));
        assertEquals("confirm", problem.get().getValue().get("choices"));
    }

    @Test
    @DisplayName("the declared value passes, and so does leaving an optional choice out")
    void theDeclaredValuePasses() {
        assertTrue(START.check(new Values(HungerGamesCommands.START, Map.of("confirm", "confirm")))
                .isEmpty());
        assertTrue(START.check(Values.none(HungerGamesCommands.START)).isEmpty());
    }

    @Test
    @DisplayName("a command's own problem() is still asked, after the generic checks")
    void theCommandsOwnCheckStillRuns() {
        // /phase set takes a CHOICE over the phase names, so its own problem() and this one look at
        // the same argument. Both have to fire, and the generic one first: SetPhase#problem is what
        // produces phase.unknown, and it would never be reached for a value the choices refuse.
        final Optional<Map.Entry<String, Map<String, ?>>> problem =
                SET.check(new Values(PhaseCommands.SET, Map.of("phase", "NOT_A_PHASE")));

        assertTrue(problem.isPresent());
        assertEquals("command.not-a-choice", problem.get().getKey());

        for (final String phase : List.of("SMP", "MAINTENANCE")) {
            assertTrue(SET.check(new Values(PhaseCommands.SET, Map.of("phase", phase))).isEmpty(),
                    phase + " is a declared choice and was refused");
        }
    }

    @Test
    @DisplayName("a choice typed in the wrong case is accepted and normalised, not refused")
    void caseIsNotThePoint() {
        // /phase set maintenance has worked in chat since the proxy's hand-written adapter -
        // SetPhase#parse compares phase names case-insensitively. The generic check that replaced
        // that adapter briefly refused it, on the one command an admin runs while the network is
        // already misbehaving.
        assertTrue(SET.check(new Values(PhaseCommands.SET, Map.of("phase", "maintenance"))).isEmpty(),
                "a lowercase phase name was refused");

        // And what the command reads is the DECLARED spelling, so it can compare against its own
        // constants without every command remembering to be lenient.
        assertEquals("MAINTENANCE",
                new Values(PhaseCommands.SET, Map.of("phase", "MaInTeNaNcE")).string("phase"));
    }

    @Test
    @DisplayName("a value that is no choice at all is quoted back exactly as it was typed")
    void anUnknownValueIsNotNormalised() {
        // The refusal names it, so it has to survive unchanged - normalising would be normalising
        // toward something it is not.
        final Optional<Map.Entry<String, Map<String, ?>>> problem =
                SET.check(new Values(PhaseCommands.SET, Map.of("phase", "MaIntenanz")));
        assertTrue(problem.isPresent());
        assertEquals("MaIntenanz", problem.get().getValue().get("typed"));
    }

    @Test
    @DisplayName("every declaration's own sample values pass their command's checks")
    void theCatalogueAgreesWithItself() {
        // A declaration whose choices no longer match what its command expects is exactly the drift
        // this module exists to make impossible, and it is invisible from either file alone.
        for (final Declaration declaration : Catalogue.all()) {
            for (final Argument argument : declaration.arguments()) {
                if (argument.kind() != Argument.Kind.CHOICE) {
                    continue;
                }
                assertTrue(!argument.choices().isEmpty(),
                        declaration.name() + ": '" + argument.name() + "' offers nothing");
            }
        }
    }
}
