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
    @DisplayName("somebody joining between the two reads is not a failure")
    void aCacheLargerThanTheReportedCountIsStillComplete() {
        // getMemberCache().size() and getMemberCount() are read a moment apart, so the cache can
        // legitimately be one ahead. Greater-than-or-equal rather than equal for exactly that.
        assertTrue(GuildState.memberCacheLooksComplete(121, 120));
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
