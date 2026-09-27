package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When the startup reconcile may treat unseen members as gone, which also deletes their account links.
 *
 * It may only when the member cache demonstrably holds the whole guild.
 */
class GuildStateTest {

    @Test
    void aFullyChunkedCacheMayDeleteLinks() {
        assertTrue(GuildState.memberCacheLooksComplete(120, 120));
    }

    @Test
    void somebodyLeavingMidPassLeavesTheSnapshotOneAheadAndThatIsStillComplete() {
        // The snapshot is taken first, so a mid-pass leave is still in `seen`.
        assertTrue(GuildState.memberCacheLooksComplete(121, 120));
    }

    @Test
    void somebodyJoiningMidPassMustBlockDeletionNotAuthoriseIt() {
        // A member who joined after the snapshot is missing from `seen`; true would delete their fresh link.
        assertFalse(GuildState.memberCacheLooksComplete(120, 121));
    }

    @Test
    void aCacheThatChunkedShortMustNotDeleteAnything() {
        // 3 of 120 members loaded must not read as "117 people left".
        assertFalse(GuildState.memberCacheLooksComplete(3, 120));
    }

    @Test
    void anEmptyCacheIsNeverTrusted() {
        assertFalse(GuildState.memberCacheLooksComplete(0, 120));
    }

    @Test
    void aGuildSizeDiscordHasNotToldUsIsNotAnAnswer() {
        // getMemberCount() can be zero or negative before anything is reported.
        assertFalse(GuildState.memberCacheLooksComplete(120, 0));
        assertFalse(GuildState.memberCacheLooksComplete(120, -1));
    }
}
