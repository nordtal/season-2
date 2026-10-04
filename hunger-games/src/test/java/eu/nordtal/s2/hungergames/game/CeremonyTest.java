package eu.nordtal.s2.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.PlayerContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** The ceremony names players the way the tab list does, never by an account identifier. */
class CeremonyTest {

    private static final Messages MESSAGES = Messages.load("messages/hunger-games", Locale.ENGLISH, Locale.GERMAN);

    private static final UUID IDA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OLE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Map<UUID, PlayerContext> NAMES = Map.of(
            IDA, PlayerContext.of(PlayerId.of(new UUID(0L, 1L)), "IdaMines"),
            OLE, PlayerContext.of(PlayerId.of(new UUID(0L, 2L)), "OleBuilds"));

    @Test
    void aWinAndItsKillsNameTheMinecraftAccounts() {
        final String text =
                render(new Ceremony.Decision(WinTracker.Outcome.win(IDA), null, Map.of(IDA, 3, OLE, 1), NAMES));

        assertTrue(text.contains("IdaMines has won"), text);
        assertTrue(text.contains("IdaMines - 3 kills"), text);
        assertTrue(text.contains("OleBuilds - 1 kill\n"), text);
        assertNoIdentifier(text);
    }

    @Test
    void aTieBrokenWinNamesTheWinnerByName() {
        final String text = render(
                new Ceremony.Decision(WinTracker.Outcome.tieBroken(OLE, 2, 1), null, Map.of(IDA, 1, OLE, 2), NAMES));

        assertTrue(text.contains("OleBuilds"), text);
        assertNoIdentifier(text);
    }

    @Test
    void aWinnerTheIdentityServiceHasNoNameForIsNamedByNoIdentifier() {
        final String text = render(new Ceremony.Decision(WinTracker.Outcome.win(IDA), null, Map.of(IDA, 1), Map.of()));

        assertTrue(text.contains("has won"), text);
        assertNoIdentifier(text);
    }

    private static void assertNoIdentifier(final String text) {
        for (final UUID member : List.of(IDA, OLE)) {
            assertFalse(text.contains(member.toString()), "a member UUID reached the chat: " + text);
        }
    }

    private static String render(final Ceremony.Decision decision) {
        final StringBuilder out = new StringBuilder();
        for (final MessageRef line : Ceremony.lines(decision)) {
            out.append(PlainTextComponentSerializer.plainText()
                            .serialize(MessageRenderer.of(MESSAGES).format(Locale.ENGLISH, line)))
                    .append('\n');
        }
        return out.toString();
    }
}
