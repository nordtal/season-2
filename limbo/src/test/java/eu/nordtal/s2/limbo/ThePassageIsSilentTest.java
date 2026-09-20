package eu.nordtal.s2.limbo;

import eu.nordtal.s2.limbo.listener.PresenceListener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nobody in the waiting room reaches anybody else (season-2-ops/141).
 *
 * <p>Till, 2026-09-20: "auf den limbos sollte voice chat UND text chat generell nicht
 * funktionieren. Spieler sollten sich auch gegenseitig nicht sehen und hören." Three of those four
 * were already true and one was not: chat was cancelled and {@code /msg} was not, which is the same
 * sentence delivered through a different door.</p>
 *
 * <p>Half of this file is therefore the rule and half is a source rule, for the reason
 * {@code WaitingTextTest} gives at the top of this module: everything here is Bukkit, and a handler
 * that stops existing cannot be noticed by any test that has no server to run on. Reading the file
 * is the cheapest guard that can see it at all.</p>
 */
class ThePassageIsSilentTest {

    private static final Path LISTENER =
            Path.of("src/main/java/eu/nordtal/s2/limbo/listener/PresenceListener.java");

    @Test
    @DisplayName("a player's commands are swallowed and an admin's are not")
    void theRule() {
        assertTrue(PresenceListener.mutes(false),
                "a player on the limbo can reach somebody with /msg");
        assertFalse(PresenceListener.mutes(true),
                "/limbo is the one command anybody would run here, and an admin is who runs it");
    }

    @Test
    @DisplayName("chat and commands are both cancelled, and the second one is the new half")
    void bothDoorsAreShut() throws IOException {
        final String source = Files.readString(LISTENER);

        assertTrue(source.contains("public void onChat(final AsyncChatEvent event)"),
                "chat is deliverable again");
        assertTrue(source.contains("public void onCommand(final PlayerCommandPreprocessEvent event)"),
                "/msg is deliverable again, which is chat with a different prefix");
    }

    @Test
    @DisplayName("hiding is done in both directions, because hidePlayer is one-way")
    void nobodySeesAnybody() throws IOException {
        // Point 3 of the ticket, and the reason point 4 costs nothing: a hidden player is not
        // rendered by the other client, and a player the client does not render makes no sound in
        // it either. Everybody here also flies (WaitingRoom#receive), so there are no footsteps to
        // suppress in the first place - which is why "hearing" needed no mechanism of its own.
        final String source = Files.readString(LISTENER);

        assertTrue(source.contains("joining.hidePlayer(plugin, other)"), "one direction is gone");
        assertTrue(source.contains("other.hidePlayer(plugin, joining)"), "the other direction is gone");
    }
}
