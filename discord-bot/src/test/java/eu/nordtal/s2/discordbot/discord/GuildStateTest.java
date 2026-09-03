package eu.nordtal.s2.discordbot.discord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule in {@link GuildState} that can be wrong catastrophically rather than untidily.
 *
 * <p>The startup reconcile's third pass is "everybody we know about who is in neither the member
 * cache nor the ban list has left". Writing {@code LEFT} on a bad guess costs nothing - the next
 * reconcile puts it back. Since 2026-09-03 that pass also deletes the account link, and that does
 * not come back: every affected member has to log in, read a fresh code off the disconnect screen
 * and type it into Discord. So the pass only deletes when the cache demonstrably holds the whole
 * guild, and this is that test.
 */
class GuildStateTest {

    @Test
    void aFullyChunkedCacheMayDeleteLinks() {
        assertTrue(GuildState.memberCacheLooksComplete(120, 120));
    }

    @Test
    @DisplayName("somebody LEAVING mid-pass leaves the snapshot one ahead, and that is still complete")
    void aSnapshotLargerThanTheLaterCountIsStillComplete() {
        // reconcile() takes the member snapshot first and reads getMemberCount() after it, so this
        // is the leave case: the member is still in the snapshot, therefore in `seen`, therefore
        // never reached by the pass that deletes. Nothing is at risk, so it does not block.
        assertTrue(GuildState.memberCacheLooksComplete(121, 120));
    }

    @Test
    @DisplayName("somebody JOINING mid-pass must block deletion, not authorise it")
    void aCountThatGrewPastTheSnapshotBlocksDeletion() {
        // The order of the two reads is what makes this the safe direction. A member who joins
        // after the snapshot is missing from `seen` and present in the later count - so if this
        // returned true, the third pass would mark them LEFT and delete the link of somebody who
        // had just arrived. Reading the count last turns that race into a refusal.
        assertFalse(GuildState.memberCacheLooksComplete(120, 121));
    }

    @Test
    void aCacheThatChunkedShortMustNotDeleteAnything() {
        // This is the failure being defended against: 3 of 120 members loaded would otherwise be
        // read as "117 people left the guild".
        assertFalse(GuildState.memberCacheLooksComplete(3, 120));
    }

    @Test
    void anEmptyCacheIsNeverTrusted() {
        assertFalse(GuildState.memberCacheLooksComplete(0, 120));
    }

    @Test
    @DisplayName("a guild size Discord has not told us is not an answer")
    void anUnknownMemberCountBlocksDeletion() {
        // getMemberCount() can be zero or negative when nothing has been reported. Both mean "I do
        // not know", and "I do not know" must never authorise a delete.
        assertFalse(GuildState.memberCacheLooksComplete(120, 0));
        assertFalse(GuildState.memberCacheLooksComplete(120, -1));
    }
}
