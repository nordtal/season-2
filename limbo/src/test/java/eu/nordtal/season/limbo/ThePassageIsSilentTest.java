package eu.nordtal.season.limbo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.limbo.listener.PresenceListener;
import org.junit.jupiter.api.Test;

/**
 * Nobody in the waiting room reaches anybody else: no chat, no commands, no seeing or hearing another player.
 *
 * That both handlers exist and hiding goes both ways is {@code :architecture}'s rule.
 */
class ThePassageIsSilentTest {

    @Test
    void aPlayersCommandsAreSwallowedAndAnAdminsAreNot() {
        assertTrue(PresenceListener.mutes(false), "a player on the limbo can reach somebody with /msg");
        assertFalse(
                PresenceListener.mutes(true),
                "/limbo is the one command anybody would run here, and an admin is who runs it");
    }
}
