package eu.nordtal.s2.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.MemberState;
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

    private static final UUID GAME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final HgMember IDA = new HgMember(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            TEAM,
            GAME,
            DiscordId.of("594510749410525200"),
            MemberState.OWNER,
            true);
    private static final HgMember OLE = new HgMember(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            TEAM,
            GAME,
            DiscordId.of("301234567890123456"),
            MemberState.ACCEPTED,
            true);

    private static final Map<UUID, PlayerContext> NAMES = Map.of(
            IDA.id(), PlayerContext.of(PlayerId.of(new UUID(0L, 1L)), "IdaMines"),
            OLE.id(), PlayerContext.of(PlayerId.of(new UUID(0L, 2L)), "OleBuilds"));

    @Test
    void aWinAndItsKillsNameTheMinecraftAccounts() {
        final String text = render(new Ceremony.Decision(
                WinTracker.Outcome.win(IDA.id()), null, List.of(IDA, OLE), Map.of(IDA.id(), 3, OLE.id(), 1), NAMES));

        assertTrue(text.contains("IdaMines has won"), text);
        assertTrue(text.contains("IdaMines - 3 kills"), text);
        assertTrue(text.contains("OleBuilds - 1 kill\n"), text);
        assertNoIdentifier(text);
    }

    @Test
    void aTieBrokenWinNamesTheWinnerByName() {
        final String text = render(new Ceremony.Decision(
                WinTracker.Outcome.tieBroken(OLE.id(), 2, 1),
                null,
                List.of(IDA, OLE),
                Map.of(IDA.id(), 1, OLE.id(), 2),
                NAMES));

        assertTrue(text.contains("OleBuilds"), text);
        assertNoIdentifier(text);
    }

    private static void assertNoIdentifier(final String text) {
        for (final HgMember member : List.of(IDA, OLE)) {
            assertFalse(text.contains(member.discordId().value()), "a Discord ID reached the chat: " + text);
            assertFalse(text.contains(member.id().toString()), "a member UUID reached the chat: " + text);
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
