package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The one rule in {@link GuildState} that can be wrong catastrophically rather than untidily.
 *
 * The startup reconcile's third pass is "everybody we know about who is in neither the member cache nor the ban list
 * has left". Writing {@code LEFT} on a bad guess costs nothing - the next reconcile puts it back. That pass also
 * deletes the account link, and that does not come back: every affected member has to log in, read a fresh code off
 * the disconnect screen and type it into Discord. So the pass only deletes when the cache demonstrably holds the
 * whole guild, and this is that test.
 */
class GuildStateTest {

    @Test
    void aFullyChunkedCacheMayDeleteLinks() {
        assertTrue(GuildState.memberCacheLooksComplete(120, 120));
    }

    @Test
    void somebodyLeavingMidPassLeavesTheSnapshotOneAheadAndThatIsStillComplete() {
        // The snapshot is taken first, so a mid-pass leave is still in it, therefore in `seen`, therefore safe.
        assertTrue(GuildState.memberCacheLooksComplete(121, 120));
    }

    @Test
    void somebodyJoiningMidPassMustBlockDeletionNotAuthoriseIt() {
        // A member who joins after the snapshot is missing from `seen`; true here would delete their fresh link.
        assertFalse(GuildState.memberCacheLooksComplete(120, 121));
    }

    @Test
    void aCacheThatChunkedShortMustNotDeleteAnything() {
        // The failure defended against: 3 of 120 members loaded would otherwise read as "117 people left".
        assertFalse(GuildState.memberCacheLooksComplete(3, 120));
    }

    @Test
    void anEmptyCacheIsNeverTrusted() {
        assertFalse(GuildState.memberCacheLooksComplete(0, 120));
    }

    @Test
    void aGuildSizeDiscordHasNotToldUsIsNotAnAnswer() {
        // getMemberCount() can be zero or negative when nothing has been reported; neither authorises a delete.
        assertFalse(GuildState.memberCacheLooksComplete(120, 0));
        assertFalse(GuildState.memberCacheLooksComplete(120, -1));
    }
}
