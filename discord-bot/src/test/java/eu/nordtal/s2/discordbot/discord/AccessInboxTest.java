package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.access.AccessRequest;
import eu.nordtal.s2.common.access.AccessRequestKind;
import eu.nordtal.s2.common.access.AccessRequestSource;
import eu.nordtal.s2.common.access.AccessRequestStatus;
import eu.nordtal.s2.common.access.AccessRequests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Which verb a request row's kind dispatches to, who it is filed under, and what goes into {@code result}. */
class AccessInboxTest {

    /** Every call, in order, as text. */
    private final List<String> carriedOut = new ArrayList<>();

    private final AccessChanges effects = new AccessChanges() {

        @Override
        public Instant grant(final String discordId, final int days, final Actor by) {
            carriedOut.add("grant " + discordId + " " + days + " by " + by.filed());
            return Instant.parse("2026-10-20T00:00:00Z");
        }

        @Override
        public int revoke(final String discordId, final Actor by) {
            carriedOut.add("revoke " + discordId + " by " + by.filed());
            return 2;
        }

        @Override
        public boolean unlink(final String discordId, final Actor by) {
            carriedOut.add("unlink " + discordId + " by " + by.filed());
            return true;
        }

        @Override
        public AccessChanges.Settled settle(final String reference, final Actor by) {
            carriedOut.add("settle " + reference + " by " + by.filed());
            return new AccessChanges.Settled(
                    AccessChanges.Settlement.BOOKED, Instant.parse("2026-11-01T00:00:00Z"), 30, "OPEN");
        }

        @Override
        public void setPlaytime(final String discordId, final long seconds, final Actor by) {
            carriedOut.add("playtime " + discordId + " " + seconds + " by " + by.filed());
        }

        @Override
        public boolean reloadMessages() {
            carriedOut.add("reload");
            return reloadSucceeds;
        }

        @Override
        public java.util.List<String> unknownOverrideKeys() {
            return unknownKeys;
        }
    };

    private boolean reloadSucceeds = true;
    private List<String> unknownKeys = List.of();

    private final Inbox inbox = new Inbox();

    private final AccessInbox subject = new AccessInbox(inbox, effects, LoggerFactory.getLogger(AccessInboxTest.class));

    private static AccessRequest row(
            final long id,
            final AccessRequestKind kind,
            final String subject,
            final String argument,
            final String requestedBy) {
        return new AccessRequest(
                id,
                kind,
                AccessRequestStatus.RUNNING,
                subject,
                argument,
                AccessRequestSource.STEWARD,
                requestedBy,
                Instant.now(),
                Instant.now().plusSeconds(120),
                Instant.now(),
                null,
                null);
    }

    @Test
    void eachKindReachesItsOwnEffect() {
        inbox.waiting.add(row(1, AccessRequestKind.GRANT, "400000000000000002", "30", "admin"));
        inbox.waiting.add(row(2, AccessRequestKind.REVOKE, "400000000000000003", null, "admin"));
        inbox.waiting.add(row(3, AccessRequestKind.UNLINK, "400000000000000004", null, "admin"));
        inbox.waiting.add(row(4, AccessRequestKind.SETTLE, "NT-7", null, "admin"));
        inbox.waiting.add(row(5, AccessRequestKind.SET_PLAYTIME, "400000000000000005", "7200", "admin"));

        assertEquals(5, subject.drain(), "one pass drains the queue, not one row per wake-up");

        assertEquals(
                List.of(
                        "grant 400000000000000002 30 by admin",
                        "revoke 400000000000000003 by admin",
                        "unlink 400000000000000004 by admin",
                        "settle NT-7 by admin",
                        "playtime 400000000000000005 7200 by admin"),
                carriedOut);
    }

    @Test
    void theAnswerGoesBackIntoTheRowAsJsonASurfaceCanRead() {
        inbox.waiting.add(row(1, AccessRequestKind.GRANT, "400000000000000002", "30", "admin"));
        inbox.waiting.add(row(2, AccessRequestKind.REVOKE, "400000000000000003", null, "admin"));
        inbox.waiting.add(row(3, AccessRequestKind.SETTLE, "NT-7", null, "admin"));

        subject.drain();

        assertEquals("{\"until\":\"2026-10-20T00:00:00Z\"}", inbox.settled.get(1L));
        assertEquals("{\"revoked\":\"2\"}", inbox.settled.get(2L));
        assertEquals(
                "{\"outcome\":\"BOOKED\",\"days\":\"30\"," + "\"until\":\"2026-11-01T00:00:00Z\",\"was\":\"OPEN\"}",
                inbox.settled.get(3L));
        assertTrue(inbox.ok.get(1L));
    }

    /** A failed grant is recorded and never retried, because running it twice doubles the days. */
    @Test
    void aFailureIsRecordedOnTheRowAndTheNextOneStillRuns() {
        final AccessInbox throwing =
                new AccessInbox(inbox, new ThrowingOnGrant(), LoggerFactory.getLogger(AccessInboxTest.class));
        inbox.waiting.add(row(1, AccessRequestKind.GRANT, "400000000000000002", "30", "admin"));
        inbox.waiting.add(row(2, AccessRequestKind.GRANT, "400000000000000003", "7", "admin"));

        assertEquals(2, throwing.drain());

        assertFalse(inbox.ok.get(1L));
        assertEquals("{\"error\":\"the guild said no\"}", inbox.settled.get(1L));
        assertFalse(inbox.ok.get(2L), "the loop carries on rather than stopping at the first one");
    }

    /** An argument that is not a number fails the row rather than granting zero and looking like success. */
    @Test
    void aMalformedArgumentFailsTheRowRatherThanGrantingNothing() {
        inbox.waiting.add(row(1, AccessRequestKind.GRANT, "400000000000000002", "thirty", "admin"));

        subject.drain();

        assertFalse(inbox.ok.get(1L));
        assertTrue(carriedOut.isEmpty(), "nothing was granted");
    }

    /** A row past its patience is expired by the bot's pass rather than left PENDING. */
    @Test
    void everyPassGivesUpOnWhatWasNeverPickedUp() {
        subject.drain();
        subject.drain();

        assertEquals(2, inbox.sweeps, "the sweep is part of a pass, not something a caller adds");
    }

    /** A reload answers with nothing to report, with typos named, or FAILED when the bundle no longer parses. */
    @Test
    void aReloadNamesTheKeysNobodyDeclaresAndAFailedOneIsAFailure() {
        inbox.waiting.add(row(1, AccessRequestKind.RELOAD_MESSAGES, "access", null, "admin"));
        subject.drain();
        assertEquals(List.of("reload"), carriedOut);
        assertTrue(inbox.ok.get(1L));
        assertEquals("{\"unknown\":\"\"}", inbox.settled.get(1L));

        unknownKeys = List.of("dm.grantd", "dm.revokd");
        inbox.waiting.add(row(2, AccessRequestKind.RELOAD_MESSAGES, "access", null, "admin"));
        subject.drain();
        assertEquals("{\"unknown\":\"dm.grantd,dm.revokd\"}", inbox.settled.get(2L));

        reloadSucceeds = false;
        inbox.waiting.add(row(3, AccessRequestKind.RELOAD_MESSAGES, "access", null, "admin"));
        subject.drain();
        assertFalse(
                inbox.ok.get(3L),
                "a bundle that no longer parses leaves the running one in place; reporting that as"
                        + " a success is how a saved change silently does nothing");
    }

    @Test
    void aRowNobodySignedIsStillFiledUnderSomething() {
        inbox.waiting.add(row(1, AccessRequestKind.REVOKE, "400000000000000002", null, null));

        subject.drain();

        assertEquals(List.of("revoke 400000000000000002 by unsigned"), carriedOut);
    }

    @Test
    void aQuotationMarkInAFailureDoesNotProduceUnreadableJson() {
        assertEquals(
                "{\"error\":\"he said \\\"no\\\"\\nand left\"}", AccessInbox.json("error", "he said \"no\"\nand left"));
        assertEquals("{\"until\":null}", AccessInbox.json("until", null));
    }

    private static final class ThrowingOnGrant implements AccessChanges {

        @Override
        public Instant grant(final String discordId, final int days, final Actor by) {
            throw new IllegalStateException("the guild said no");
        }

        @Override
        public int revoke(final String discordId, final Actor by) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean unlink(final String discordId, final Actor by) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccessChanges.Settled settle(final String reference, final Actor by) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPlaytime(final String discordId, final long seconds, final Actor by) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean reloadMessages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<String> unknownOverrideKeys() {
            throw new UnsupportedOperationException();
        }
    }

    /** The queue without a database: claim takes from the front, finish records the answer. */
    private static final class Inbox implements AccessRequests {

        private final Deque<AccessRequest> waiting = new ArrayDeque<>();
        private final java.util.Map<Long, String> settled = new java.util.HashMap<>();
        private final java.util.Map<Long, Boolean> ok = new java.util.HashMap<>();
        private int sweeps;

        @Override
        public AccessRequest submit(final NewAccessRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccessRequest submit(final NewAccessRequest request, final Duration patience) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AccessRequest> claim() {
            return Optional.ofNullable(waiting.poll());
        }

        @Override
        public void finish(final long id, final boolean carriedOut, final String result) {
            settled.put(id, result);
            ok.put(id, carriedOut);
        }

        @Override
        public Optional<AccessRequest> outcome(final long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AccessRequest> pending() {
            return List.of();
        }

        @Override
        public int expireDue() {
            sweeps++;
            return 0;
        }

        @Override
        public int purge(final Duration age) {
            return 0;
        }
    }
}
