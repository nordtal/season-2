package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesEffects;
import eu.nordtal.s2.commands.hungergames.StartGame;
import eu.nordtal.s2.common.message.MessageRef;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link NordtalCommand#check}: the generic argument checks, and how they meet a command's own.
 *
 * Brigadier types a choice as a plain word, so without this {@code /hg start yes} would count as confirming.
 */
class ChoiceCheckTest {

    private static final NordtalCommand<HungerGamesEffects> START = new StartGame();

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
    void aChoiceTypedInTheWrongCaseIsAcceptedAndNormalisedNotRefused() {
        // A choice matches regardless of case.
        assertTrue(
                START.check(new Values(HungerGamesCommands.START, Map.of("confirm", "CONFIRM")))
                        .isEmpty(),
                "an uppercase confirmation was refused");

        // What the command reads is the declared spelling.
        assertEquals("confirm", new Values(HungerGamesCommands.START, Map.of("confirm", "CoNfIrM")).string("confirm"));
    }

    @Test
    void aValueThatIsNoChoiceAtAllIsQuotedBackExactlyAsItWasTyped() {
        // The refusal names the typed value, so it has to survive unchanged.
        final Optional<MessageRef> problem =
                START.check(new Values(HungerGamesCommands.START, Map.of("confirm", "BeStätigen")));
        assertTrue(problem.isPresent());
        assertEquals("BeStätigen", problem.get().args().get("typed"));
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
