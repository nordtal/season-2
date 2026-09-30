package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which effect a kind reaches, who it is filed under, and what goes into the answer. */
class BotInboxTest {

    private static final DiscordId ADMIN = DiscordId.of("400000000000000001");

    /** Every call, in order, as text. */
    private final List<String> carriedOut = new ArrayList<>();

    private boolean reloadSucceeds = true;
    private List<String> unknownKeys = List.of();

    private final AccessChanges effects = new AccessChanges() {

        @Override
        public Instant grant(final DiscordId discordId, final int days, final Actor by) {
            carriedOut.add("grant " + discordId + " " + days + " by " + by.filed());
            return Instant.parse("2026-10-20T00:00:00Z");
        }

        @Override
        public int revoke(final DiscordId discordId, final Actor by) {
            carriedOut.add("revoke " + discordId + " by " + by.filed());
            return 2;
        }

        @Override
        public boolean unlink(final DiscordId discordId, final Actor by) {
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
        public void setPlaytime(final DiscordId discordId, final long seconds, final Actor by) {
            carriedOut.add("playtime " + discordId + " " + seconds + " by " + by.filed());
        }

        @Override
        public boolean reloadMessages() {
            carriedOut.add("reload");
            return reloadSucceeds;
        }

        @Override
        public List<String> unknownOverrideKeys() {
            return unknownKeys;
        }
    };

    private final BotInbox subject = new BotInbox(effects);

    private static Request<BotRequest> row(final BotRequest payload, final eu.nordtal.s2.database.Actor actor) {
        return new Request<>(
                1,
                BotRequest.TABLE.kindOf(payload),
                payload,
                InboxStatus.RUNNING,
                actor,
                Instant.now(),
                Instant.now(),
                null,
                Instant.now(),
                null,
                null);
    }

    private String answer(final BotRequest payload) {
        final Outcome outcome = subject.handle(row(payload, eu.nordtal.s2.database.Actor.person(ADMIN)));
        return Json.encode(assertInstanceOf(Outcome.Done.class, outcome).answer());
    }

    @Test
    void eachKindReachesItsOwnEffect() {
        final DiscordId someone = DiscordId.of("400000000000000002");
        answer(new BotRequest.Grant(someone, 30));
        answer(new BotRequest.Revoke(someone));
        answer(new BotRequest.Unlink(someone));
        answer(new BotRequest.Settle("NT-7"));
        answer(new BotRequest.SetPlaytime(someone, 7200));

        assertEquals(
                List.of(
                        "grant 400000000000000002 30 by 400000000000000001",
                        "revoke 400000000000000002 by 400000000000000001",
                        "unlink 400000000000000002 by 400000000000000001",
                        "settle NT-7 by 400000000000000001",
                        "playtime 400000000000000002 7200 by 400000000000000001"),
                carriedOut);
    }

    @Test
    void theAnswerIsAFlatObjectOfStringsASurfaceCanRead() {
        final DiscordId someone = DiscordId.of("400000000000000002");
        assertEquals("{\"until\":\"2026-10-20T00:00:00Z\"}", answer(new BotRequest.Grant(someone, 30)));
        assertEquals("{\"revoked\":\"2\"}", answer(new BotRequest.Revoke(someone)));
        assertEquals(
                "{\"outcome\":\"BOOKED\",\"days\":\"30\",\"until\":\"2026-11-01T00:00:00Z\",\"was\":\"OPEN\"}",
                answer(new BotRequest.Settle("NT-7")));
    }

    /** A reload answers with the keys nobody declares, and throws when the bundle no longer parses. */
    @Test
    void aReloadNamesTheKeysNobodyDeclaresAndAFailedOneThrows() {
        assertEquals("{\"unknown\":\"\"}", answer(new BotRequest.ReloadMessages("access")));

        unknownKeys = List.of("dm.grantd", "dm.revokd");
        assertEquals("{\"unknown\":\"dm.grantd,dm.revokd\"}", answer(new BotRequest.ReloadMessages("access")));

        reloadSucceeds = false;
        // The inbox fails a request whose handler throws: reporting it as done is how a saved change does nothing.
        assertThrows(IllegalStateException.class, () -> answer(new BotRequest.ReloadMessages("access")));
    }

    @Test
    void aRowStewardAskedForIsFiledAsTheSystem() {
        subject.handle(
                row(new BotRequest.Revoke(DiscordId.of("400000000000000002")), eu.nordtal.s2.database.Actor.STEWARD));

        // The audit's own reading of no actor: the system, never a made-up name in the id column.
        assertEquals(List.of("revoke 400000000000000002 by null"), carriedOut);
    }
}
