package eu.nordtal.s2.limbo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.limbo.listener.PresenceListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Nobody in the waiting room reaches anybody else: no chat, no commands, no seeing or hearing another player.
 *
 * Half of this file is therefore the rule and half is a source rule, for the reason {@code WaitingTextTest} gives at
 * the top of this module: everything here is Bukkit, and a handler that stops existing cannot be noticed by any test
 * that has no server to run on. Reading the file is the cheapest guard that can see it at all.
 */
class ThePassageIsSilentTest {

    private static final Path LISTENER = Path.of("src/main/java/eu/nordtal/s2/limbo/listener/PresenceListener.java");

    @Test
    void aPlayersCommandsAreSwallowedAndAnAdminsAreNot() {
        assertTrue(PresenceListener.mutes(false), "a player on the limbo can reach somebody with /msg");
        assertFalse(
                PresenceListener.mutes(true),
                "/limbo is the one command anybody would run here, and an admin is who runs it");
    }

    @Test
    void chatAndCommandsAreBothCancelled() throws IOException {
        final String source = Files.readString(LISTENER);

        assertTrue(source.contains("public void onChat(final AsyncChatEvent event)"), "chat is deliverable again");
        assertTrue(
                source.contains("public void onCommand(final PlayerCommandPreprocessEvent event)"),
                "/msg is deliverable again, which is chat with a different prefix");
    }

    @Test
    void hidingIsDoneInBothDirections() throws IOException {
        // A client renders nobody it does not see, so a hidden player also makes no sound to it.
        final String source = Files.readString(LISTENER);

        assertTrue(source.contains("joining.hidePlayer(plugin, other)"), "one direction is gone");
        assertTrue(source.contains("other.hidePlayer(plugin, joining)"), "the other direction is gone");
    }
}
