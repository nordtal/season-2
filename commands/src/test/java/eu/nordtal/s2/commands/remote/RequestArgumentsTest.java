package eu.nordtal.s2.commands.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The wire format of a travelling command's arguments.
 *
 * The round trip is asserted over every declaration and not over examples, because the format is
 * a line, and it works because {@link Declaration}'s own invariants make the split
 * unambiguous - one greedy argument at most, last, and no other kind can hold a space. That is a
 * claim about <em>every</em> declaration rather than about the ones somebody thought to write a case
 * for, so the test walks {@link Catalogue} and would fail the moment a command is declared in a
 * shape the line cannot carry. A JSON column would not have needed the argument; it would have cost
 * a parser this repository deliberately does not have.
 */
class RequestArgumentsTest {

    @Test
    void everyDeclaredCommandsArgumentsSurviveTheRoundTrip() {
        for (final Declaration declaration : Catalogue.all()) {
            final Values sent = sample(declaration);
            final String line = RequestArguments.encode(declaration, sent);
            final Values back = RequestArguments.decode(declaration, line);

            for (final Argument argument : declaration.arguments()) {
                assertEquals(
                        sent.raw(argument.name()),
                        back.raw(argument.name()),
                        declaration.name() + ": argument '" + argument.name() + "' did not survive being written as \""
                                + line + "\"");
            }
        }
    }

    @Test
    void aCommandWithNoArgumentsIsAnEmptyLineNotAMissingOne() {
        final Declaration declaration =
                new Declaration(List.of("smp", "reload"), Target.SMP, Set.of(Surface.GAME), true, false, List.of());

        assertEquals("", RequestArguments.encode(declaration, Values.none(declaration)));
        // And it reads back as a command with nothing supplied, rather than as an error.
        assertEquals(0, countSupplied(declaration, RequestArguments.decode(declaration, "")));
    }

    @Test
    void aGreedyArgumentKeepsItsSpacesBecauseItIsLast() {
        final Declaration declaration = new Declaration(
                List.of("phase", "launch"),
                Target.PROXY,
                Set.of(Surface.GAME),
                true,
                false,
                List.of(Argument.greedy("when")));

        final Values values = new Values(declaration, Map.of("when", "2026-10-01 18:00"));
        final String line = RequestArguments.encode(declaration, values);

        assertEquals("2026-10-01 18:00", line);
        assertEquals(
                "2026-10-01 18:00", RequestArguments.decode(declaration, line).string("when"));
    }

    @Test
    void aWordCarryingASpaceIsRefusedRatherThanSilentlySplit() {
        // Only reachable if an adapter parsed something as the wrong kind - so it is a programming mistake.
        final Declaration declaration = new Declaration(
                List.of("smp", "objective", "complete"),
                Target.SMP,
                Set.of(Surface.GAME),
                true,
                true,
                List.of(Argument.word("key")));

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> RequestArguments.encode(declaration, new Values(declaration, Map.of("key", "ancient debris"))));
        assertTrue(refused.getMessage().contains("key"), refused.getMessage());
    }

    @Test
    void aMissingRequiredArgumentIsADisagreementBetweenAdaptersNotADefault() {
        final Declaration declaration = new Declaration(
                List.of("smp", "aura"),
                Target.SMP,
                Set.of(Surface.GAME),
                true,
                false,
                List.of(Argument.player("player"), Argument.integer("delta", -10000, 10000)));

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> RequestArguments.decode(declaration, "11111111-2222-3333-4444-555555555555"));
        assertTrue(refused.getMessage().contains("delta"), refused.getMessage());
    }

    @Test
    void anIntegerOutsideItsDeclaredBoundsDoesNotReachTheCommand() {
        // The bounds are on the declaration, so Brigadier and JDA both enforce them where the command was typed. This.
        final Declaration declaration = new Declaration(
                List.of("smp", "aura"),
                Target.SMP,
                Set.of(Surface.GAME),
                true,
                false,
                List.of(Argument.integer("delta", -10000, 10000)));

        assertThrows(IllegalArgumentException.class, () -> RequestArguments.decode(declaration, "99999"));
        assertEquals(-10000, RequestArguments.decode(declaration, "-10000").integer("delta"));
    }

    @Test
    void aChoiceThatIsNotOneOfThemIsRefused() {
        final Declaration declaration = new Declaration(
                List.of("phase", "set"),
                Target.PROXY,
                Set.of(Surface.GAME),
                true,
                true,
                List.of(Argument.choice("phase", List.of("SMP", "MAINTENANCE"))));

        assertThrows(IllegalArgumentException.class, () -> RequestArguments.decode(declaration, "PRE_EVENT"));
    }

    @Test
    void aPlayerArgumentArrivesResolvedAndAnythingElseIsRefused() {
        final Declaration declaration = new Declaration(
                List.of("smp", "access"),
                Target.SMP,
                Set.of(Surface.GAME),
                true,
                false,
                List.of(Argument.player("player")));

        final UUID who = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals(who, RequestArguments.decode(declaration, who.toString()).player("player"));

        // A name would mean the target has to resolve it, on a server the player may not be on.
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> RequestArguments.decode(declaration, "Notch"));
        assertTrue(refused.getMessage().contains("UUID"), refused.getMessage());
    }

    @Test
    void tokensAfterTheLastDeclaredArgumentAreRefused() {
        final Declaration declaration =
                new Declaration(List.of("smp", "reload"), Target.SMP, Set.of(Surface.GAME), true, false, List.of());

        assertThrows(IllegalArgumentException.class, () -> RequestArguments.decode(declaration, "please"));
    }

    @Test
    void anAbsentOptionalArgumentEndsTheLineAndIsAbsentOnTheFarSide() {
        final Declaration declaration = new Declaration(
                List.of("hg", "start"),
                Target.HUNGER_GAMES,
                Set.of(Surface.GAME),
                true,
                true,
                List.of(Argument.word("mode").optional()));

        assertEquals("", RequestArguments.encode(declaration, Values.none(declaration)));
        assertTrue(
                RequestArguments.decode(declaration, "").optionalString("mode").isEmpty());
        assertEquals("duo", RequestArguments.decode(declaration, "duo").string("mode"));
    }

    @Test
    void aChoiceSentInAnotherCaseArrivesInTheDeclaredSpelling() {
        // The asking adapter may have taken `maintenance` off a chat line.
        final Declaration declaration = new Declaration(
                List.of("phase", "set"),
                Target.PROXY,
                Set.of(Surface.GAME),
                true,
                true,
                List.of(Argument.choice("phase", List.of("SMP", "MAINTENANCE"))));

        assertEquals(
                "MAINTENANCE",
                RequestArguments.decode(declaration, "maintenance").string("phase"));
        assertThrows(IllegalArgumentException.class, () -> RequestArguments.decode(declaration, "wartung"));
    }

    @Test
    void aGreedyValueThatBeginsOrEndsWithASpaceIsRefusedNotSilentlyTrimmed() {
        // decode() walks past the spaces between arguments before it reads the greedy one.
        final Declaration declaration = new Declaration(
                List.of("phase", "launch"),
                Target.PROXY,
                Set.of(Surface.GAME),
                true,
                false,
                List.of(Argument.greedy("when")));

        for (final String typed : List.of(" 2026-10-01 18:00", "2026-10-01 18:00 ")) {
            final IllegalArgumentException refused = assertThrows(
                    IllegalArgumentException.class,
                    () -> RequestArguments.encode(declaration, new Values(declaration, Map.of("when", typed))),
                    "\"" + typed + "\" would not have survived the round trip");
            assertTrue(refused.getMessage().contains("when"), refused.getMessage());
        }
    }

    @Test
    void aValueSuppliedAfterAnAbsentOptionalIsRefusedRatherThanDropped() {
        // Declaration only forbids a REQUIRED argument after an optional one.
        final Declaration declaration = new Declaration(
                List.of("hg", "start"),
                Target.HUNGER_GAMES,
                Set.of(Surface.GAME),
                true,
                false,
                List.of(Argument.word("mode").optional(), Argument.word("note").optional()));

        final IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> RequestArguments.encode(declaration, new Values(declaration, Map.of("note", "later"))));
        assertTrue(refused.getMessage().contains("note"), refused.getMessage());
    }

    @Test
    void aDiscordIdIsAsciiDigitsAndCharacterIsdigitIsNotThatTest() {
        // Devanagari digits pass Character.isDigit. The far side hands this straight to a query and to a mention.
        final Declaration declaration = new Declaration(
                List.of("access", "revoke"),
                Target.BOT,
                Set.of(Surface.GAME),
                true,
                true,
                List.of(Argument.account("member")));

        assertEquals(
                "100000000000000009",
                RequestArguments.decode(declaration, "100000000000000009").account("member"));
        assertThrows(IllegalArgumentException.class, () -> RequestArguments.decode(declaration, "\u0967\u0968\u0969"));
    }

    /** One plausible value per declared argument, chosen from the argument's own declaration. */
    private static Values sample(final Declaration declaration) {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Argument argument : declaration.arguments()) {
            values.put(
                    argument.name(),
                    switch (argument.kind()) {
                        case WORD, REFERENCE -> "sample-key";
                        // Deliberately with spaces: a greedy argument that does not exercise them proves nothing.
                        case GREEDY_STRING -> "2026-10-01 18:00";
                        case INTEGER -> argument.min();
                        case PLAYER -> UUID.fromString("11111111-2222-3333-4444-555555555555");
                        case ACCOUNT -> "100000000000000009";
                        case CHOICE -> argument.choices().getFirst();
                    });
        }
        return new Values(declaration, values);
    }

    private static int countSupplied(final Declaration declaration, final Values values) {
        return (int) declaration.arguments().stream()
                .filter(argument -> values.raw(argument.name()).isPresent())
                .count();
    }
}
