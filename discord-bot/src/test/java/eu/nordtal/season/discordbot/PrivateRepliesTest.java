package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PrivateRepliesTest {
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    @Test
    void aNewPrivateMessageDeletesTheOneBefore() {
        assertEquals(PrivateReplies.Step.DELETE, PrivateReplies.step(held("1", 60), held("2", 0), NOW));
    }

    @Test
    void usingTheCurrentMessageAgainKeepsIt() {
        assertEquals(PrivateReplies.Step.KEEP, PrivateReplies.step(held("1", 60), held("1", 0), NOW));
    }

    @Test
    void aMessageWhoseTokenExpiredIsForgottenBecauseDiscordNoLongerLetsTheBotDeleteIt() {
        assertEquals(
                PrivateReplies.Step.FORGET,
                PrivateReplies.step(held("1", PrivateReplies.TOKEN.toSeconds()), held("2", 0), NOW));
    }

    @Test
    void aMessageOfUnknownIdIsNeverDeletedSinceItMayBeTheOneInUse() {
        assertEquals(PrivateReplies.Step.FORGET, PrivateReplies.step(held(null, 60), held("2", 0), NOW));
    }

    private static PrivateReplies.Held held(final String message, final long secondsAgo) {
        return new PrivateReplies.Held(null, message, NOW.minusSeconds(secondsAgo));
    }
}
