package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.commands.hungergames.StartGame;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.commands.phase.SetPhase;
import eu.nordtal.s2.common.message.MessageRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link NordtalCommand#check}: the generic argument checks, and how they meet a command's own.
 *
 * The gap this closes: Discord builds a dropdown for a {@link Argument.Kind#CHOICE} and its own
 * client refuses anything
 * that is not on the list; {@code RequestArguments#decode} refuses one on the way off a request row.
 * Brigadier has no enum type at all, so both chat adapters type a choice as a plain word and take
 * whatever was typed - and nothing checked it afterwards.
 *
 * {@code /hg start} is what made that expensive rather than untidy: the trailing {@code confirm}
 * is a choice, and {@link StartGame} treats <em>any</em> present value as the second step. So
 * {@code /hg start yes} would have spent an armed confirmation and begun the season's flagship event
 * below the recommended minimum, having said nothing about the word it did not understand.
 */
class ChoiceCheckTest {

    private static final NordtalCommand<HungerGamesEffects> START = new StartGame();
    private static final NordtalCommand<PhaseEffects> SET = new SetPhase();

    @Test
    void aValueThatIsNotOneOfTheDeclaredChoicesNamesItselfAndTheList() {
        final Optional<MessageRef> problem =
                START.check(new Values(HungerGamesCommands.START, Map.of("confirm", "yes")));

        assertTrue(problem.isPresent(), "/hg start yes was accepted as a confirmation");
        assertEquals("command.not-a-choice", problem.get().key());
        assertEquals("confirm", problem.get().args().get("argument"));
        assertEquals("yes", problem.get().args().get("typed"));
        assertEquals("confirm", problem.get().args().get("choices"));
    }

    @Test
    void theDeclaredValuePassesAndSoDoesLeavingAnOptionalChoiceOut() {
        assertTrue(START.check(new Values(HungerGamesCommands.START, Map.of("confirm", "confirm")))
                .isEmpty());
        assertTrue(START.check(Values.none(HungerGamesCommands.START)).isEmpty());
    }

    @Test
    void aCommandsOwnProblemIsStillAskedAfterTheGenericChecks() {
        // /phase set takes a CHOICE over the phase names.
        final Optional<MessageRef> problem = SET.check(new Values(PhaseCommands.SET, Map.of("phase", "NOT_A_PHASE")));

        assertTrue(problem.isPresent());
        assertEquals("command.not-a-choice", problem.get().key());

        for (final String phase : List.of("SMP", "MAINTENANCE")) {
            assertTrue(
                    SET.check(new Values(PhaseCommands.SET, Map.of("phase", phase)))
                            .isEmpty(),
                    phase + " is a declared choice and was refused");
        }
    }

    @Test
    void aChoiceTypedInTheWrongCaseIsAcceptedAndNormalisedNotRefused() {
        // /phase set maintenance has worked in chat since the proxy's hand-written adapter.
        assertTrue(
                SET.check(new Values(PhaseCommands.SET, Map.of("phase", "maintenance")))
                        .isEmpty(),
                "a lowercase phase name was refused");

        // And what the command reads is the DECLARED spelling.
        assertEquals("MAINTENANCE", new Values(PhaseCommands.SET, Map.of("phase", "MaInTeNaNcE")).string("phase"));
    }

    @Test
    void aValueThatIsNoChoiceAtAllIsQuotedBackExactlyAsItWasTyped() {
        // The refusal names it, so it has to survive unchanged.
        final Optional<MessageRef> problem = SET.check(new Values(PhaseCommands.SET, Map.of("phase", "MaIntenanz")));
        assertTrue(problem.isPresent());
        assertEquals("MaIntenanz", problem.get().args().get("typed"));
    }

    @Test
    void everyDeclarationsOwnSampleValuesPassTheirCommandsChecks() {
        // Catches a declaration whose choices no longer match what its command expects.
        for (final Declaration declaration : Catalogue.all()) {
            for (final Argument argument : declaration.arguments()) {
                if (argument.kind() != Argument.Kind.CHOICE) {
                    continue;
                }
                assertTrue(
                        !argument.choices().isEmpty(),
                        declaration.name() + ": '" + argument.name() + "' offers nothing");
            }
        }
    }
}
