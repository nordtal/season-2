package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Values} does when a command and its declaration disagree - which is the only way it
 * can be wrong, because a user's mistake never reaches it.
 */
class ValuesTest {

    private static final Declaration AURA = new Declaration(List.of("smp", "aura"), Target.SMP,
            Set.of(Surface.GAME, Surface.DISCORD), true, false,
            List.of(Argument.player("player"), Argument.integer("delta", -10_000, 10_000)));

    @Test
    @DisplayName("the arguments come back as what they were declared to be")
    void readsWhatWasGiven() {
        final UUID player = UUID.randomUUID();
        final Values values = new Values(AURA, Map.of("player", player, "delta", 50));

        assertEquals(player, values.player("player"));
        assertEquals(50, values.integer("delta"));
    }

    @Test
    @DisplayName("a missing argument names itself and the command, rather than reading as absent")
    void missingArgumentIsLoud() {
        final Values values = new Values(AURA, Map.of("player", UUID.randomUUID()));

        final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> values.integer("delta"));

        assertTrue(thrown.getMessage().contains("/smp aura"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("delta"), thrown.getMessage());
    }

    @Test
    @DisplayName("an argument read as the wrong kind says which two kinds disagreed")
    void wrongKindIsLoud() {
        // The adapter parsed a player as a name instead of resolving it. Without this the command
        // would get a ClassCastException from somewhere inside itself, naming neither the argument
        // nor the command.
        final Values values = new Values(AURA, Map.of("player", "Till", "delta", 1));

        final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> values.player("player"));

        assertTrue(thrown.getMessage().contains("String"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("UUID"), thrown.getMessage());
    }

    @Test
    @DisplayName("an optional argument that was not given is absent, not an error")
    void optionalIsAllowedToBeMissing() {
        final Declaration start = new Declaration(List.of("hg", "start"), Target.HUNGER_GAMES,
                Set.of(Surface.GAME), true, false, List.of(Argument.word("confirm").optional()));

        assertEquals(Optional.empty(), Values.none(start).optionalString("confirm"));
        assertEquals(Optional.of("confirm"),
                new Values(start, Map.of("confirm", "confirm")).optionalString("confirm"));
    }
}
