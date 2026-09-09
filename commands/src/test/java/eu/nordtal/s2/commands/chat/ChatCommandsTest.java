package eu.nordtal.s2.commands.chat;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.commands.Values;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /msg}, {@code /whisper} and {@code /r} decide, without a proxy under them.
 *
 * <p>Everything a private message <em>draws</em> - the flag, the admin tag, the two languages - is
 * the proxy's and needs one. What is here is the half that can be got wrong silently: which sentence
 * comes back when there is nobody on the other end, whether {@code /r} tells the two failures apart,
 * and whether the text a player typed reaches the effect untouched.</p>
 */
class ChatCommandsTest {

    private static final UUID SOMEBODY = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private final FakeChat effects = new FakeChat();

    @Test
    @DisplayName("a delivered message says nothing back - the effect has already written both lines")
    void aSentMessageIsSilent() {
        final FakeUser user = FakeUser.inGame();
        msg().run(user, values(ChatCommands.MSG, Map.of(
                ChatCommands.PLAYER, SOMEBODY, ChatCommands.MESSAGE, "hello")), effects);

        assertEquals(List.of(), user.keys(),
                "a confirmation on top of the line the sender was already shown is the same thing"
                        + " said twice");
        assertEquals(List.of(new FakeChat.Sent(SOMEBODY, "hello")), effects.sent);
    }

    @Test
    @DisplayName("the text reaches the effect exactly as typed, markup included")
    void theTextIsNeverTouched() {
        // The whole reason the effect inserts it as a component. A command that cleaned it up here
        // would be a second place deciding what a player is allowed to say, and the two would
        // disagree the first time one of them changed.
        final String awkward = "<red>hi</red> \\ {name} <click:run_command:/kill @a>";
        msg().run(FakeUser.inGame(), values(ChatCommands.MSG, Map.of(
                ChatCommands.PLAYER, SOMEBODY, ChatCommands.MESSAGE, awkward)), effects);

        assertEquals(awkward, effects.sent.getFirst().text());
    }

    @Test
    @DisplayName("a recipient who left between typing and sending is told about, not swallowed")
    void goneIsReported() {
        effects.answer = ChatEffects.Outcome.GONE;
        final FakeUser user = FakeUser.inGame();
        msg().run(user, values(ChatCommands.MSG, Map.of(
                ChatCommands.PLAYER, SOMEBODY, ChatCommands.MESSAGE, "hello")), effects);

        assertEquals(List.of("command.player-offline"), user.keys());
    }

    @Test
    @DisplayName("/r tells 'nobody has written to you' apart from 'they have gone'")
    void theTwoReplyFailuresAreDifferentSentences() {
        // A player can act on the difference: the first means type their name, the second means
        // they were there a moment ago. One sentence for both would make the first unanswerable.
        effects.answer = ChatEffects.Outcome.NO_PARTNER;
        final FakeUser noPartner = FakeUser.inGame();
        reply().run(noPartner, values(ChatCommands.REPLY, Map.of(ChatCommands.MESSAGE, "hi")),
                effects);
        assertEquals(List.of("chat.no-partner"), noPartner.keys());

        effects.answer = ChatEffects.Outcome.GONE;
        final FakeUser gone = FakeUser.inGame();
        reply().run(gone, values(ChatCommands.REPLY, Map.of(ChatCommands.MESSAGE, "hi")), effects);
        assertEquals(List.of("command.player-offline"), gone.keys());
    }

    @Test
    @DisplayName("a failure says so and never puts the message in the log")
    void aThrownEffectIsAnsweredAndNotEchoed() {
        effects.failure = new IllegalStateException("the network went away");
        final FakeUser user = FakeUser.inGame();
        msg().run(user, values(ChatCommands.MSG, Map.of(
                ChatCommands.PLAYER, SOMEBODY, ChatCommands.MESSAGE, "a secret")), effects);

        assertEquals(List.of("chat.failed"), user.keys());
        assertEquals(1, effects.warnings.size());
        assertFalse(effects.warnings.getFirst().contains("a secret"),
                "the log line carries what somebody wrote, which is the one place it would survive"
                        + " - nothing about a private message is recorded (owner, 2026-09-08)");
    }

    @Test
    @DisplayName("/whisper is the same command under a second name, not a second implementation")
    void whisperIsMsg() {
        final FakeUser user = FakeUser.inGame();
        new PrivateMessage(ChatCommands.WHISPER).run(user, values(ChatCommands.WHISPER, Map.of(
                ChatCommands.PLAYER, SOMEBODY, ChatCommands.MESSAGE, "hello")), effects);

        assertEquals(List.of(new FakeChat.Sent(SOMEBODY, "hello")), effects.sent);
        assertEquals("/whisper", new PrivateMessage(ChatCommands.WHISPER).declaration().name(),
                "the declaration decides the name, so the two cannot answer under one");
    }

    @Test
    @DisplayName("the declarations are what a player command has to be")
    void theShapeOfThem() {
        for (final Declaration declaration : ChatCommands.declarations()) {
            assertFalse(declaration.adminOnly(), declaration.name() + " is admin-only");
            assertFalse(declaration.irreversible(),
                    declaration.name() + " asks for a confirmation, which would put a retype"
                            + " between somebody and a chat message");
            assertEquals(Target.PROXY, declaration.target(),
                    declaration.name() + " is not the proxy's, so it would become a request row -"
                            + " and a chat message that travels through a table is not a chat"
                            + " message");
            assertEquals(java.util.Set.of(Surface.GAME), declaration.surfaces(),
                    declaration.name() + ": the console has no session and therefore no reply"
                            + " partner, and Discord has private messages of its own");
            final Argument last = declaration.arguments().getLast();
            assertEquals(Argument.Kind.GREEDY_STRING, last.kind(),
                    declaration.name() + " would cut the message at the first space");
            assertTrue(last.required(), declaration.name() + " would send an empty line");
        }
        assertEquals(List.of("/msg", "/whisper", "/r"),
                ChatCommands.declarations().stream().map(Declaration::name).toList());
    }

    private NordtalCommand<ChatEffects> msg() {
        return new PrivateMessage(ChatCommands.MSG);
    }

    private NordtalCommand<ChatEffects> reply() {
        return new ReplyMessage();
    }

    private static Values values(final Declaration declaration, final Map<String, Object> values) {
        return new Values(declaration, values);
    }
}
