package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The placeholder checks of a message save. */
class PlaceholdersTest {

    @Test
    void aPlaceholderTheSchemaDoesNotDeclareIsFoundADeclaredOneAndFormattingAreNot() {
        final MessageEntry won =
                entry("Duel won", List.of(new MessageArg("opponent", false), new MessageArg("_link", true)));

        assertEquals(List.of(), Placeholders.unknown(won, "<bold>{opponent}</bold> lost <_link>"));
        assertEquals(
                List.of("{oponent}", "<_player>"), Placeholders.unknown(won, "You beat {oponent} <_player> {oponent}"));
    }

    @Test
    void aRolesPropertiesAndTheGlobalsAreDeclaredTheRoleItselfIsNot() {
        final MessageEntry won = entry(
                "Duel won",
                List.of(
                        new MessageArg("winner.name", false, "player", false),
                        new MessageArg("server.name", false, "service", true)));

        assertEquals(
                List.of("{winner.nope}", "{winner}"),
                Placeholders.unknown(won, "{winner.name} {server.name} {winner.nope} {winner}"));
    }

    @Test
    void aKeyWithoutASchemaIsNeverChecked() {
        assertEquals(List.of(), Placeholders.unknown(entry(null, List.of()), "Welcome {player}"));
    }

    @Test
    void droppingANamedPlaceholderIsReportedNeverSilentlyAccepted() {
        assertEquals(List.of("<_sender>"), Placeholders.missing("<_sender> waves hello", "somebody waves hello"));
        assertEquals(List.of("{price}"), Placeholders.missing("{days} days - {price}", "{days} days - free"));
    }

    @Test
    void keepingThePlaceholderOrHavingNoneToKeepReportsNothing() {
        assertEquals(List.of(), Placeholders.missing("<_sender> waves hello", "<_sender> says hi"));
        assertEquals(List.of(), Placeholders.missing("plain text", "different plain text"));
    }

    @Test
    void aPlainFormattingTagIsNotAPlaceholderItCarriesNoDataToLose() {
        assertEquals(List.of(), Placeholders.missing("<bold>hi</bold>", "hi"));
    }

    private static MessageEntry entry(final @Nullable String name, final List<MessageArg> args) {
        return new MessageEntry(
                "key", "smp", "text", null, null, null, null, null, true, name, null, args, List.of(), null, null);
    }
}
