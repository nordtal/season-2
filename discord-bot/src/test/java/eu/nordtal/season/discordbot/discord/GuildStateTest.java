package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The startup reconcile treats unseen members as gone only when the member cache holds the whole guild. */
class GuildStateTest {

    @Test
    void aFullyChunkedCacheMayDeleteLinks() {
        assertTrue(GuildState.memberCacheLooksComplete(120, 120));
    }

    @Test
    void somebodyLeavingMidPassLeavesTheSnapshotOneAheadAndThatIsStillComplete() {
        // The snapshot is taken first, so a leave during the pass is still in `seen`.
        assertTrue(GuildState.memberCacheLooksComplete(121, 120));
    }

    @Test
    void somebodyJoiningMidPassMustBlockDeletionNotAuthoriseIt() {
        // A member who joined after the snapshot is not in `seen`, so a true result would delete their new link.
        assertFalse(GuildState.memberCacheLooksComplete(120, 121));
    }

    @Test
    void aCacheThatChunkedShortMustNotDeleteAnything() {
        // If 3 of 120 members are loaded, 117 are not counted as gone.
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
