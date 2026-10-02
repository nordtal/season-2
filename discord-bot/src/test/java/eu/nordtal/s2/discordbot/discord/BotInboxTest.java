package eu.nordtal.s2.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
            carriedOut.add("grant " + discordId + " " + days + " by " + filed(by));
            return Instant.parse("2026-10-20T00:00:00Z");
        }

        @Override
        public int revoke(final DiscordId discordId, final Actor by) {
            carriedOut.add("revoke " + discordId + " by " + filed(by));
            return 2;
        }

        @Override
        public boolean unlink(final DiscordId discordId, final Actor by) {
            carriedOut.add("unlink " + discordId + " by " + filed(by));
            return true;
        }

        @Override
        public void setPlaytime(final DiscordId discordId, final long seconds, final Actor by) {
            carriedOut.add("playtime " + discordId + " " + seconds + " by " + filed(by));
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

    private final BotInbox subject = new BotInbox(
            effects,
            (tag, text) -> {
                carriedOut.add("announce " + tag + " " + text);
                return !tag.equals("fr");
            },
            alert -> {
                carriedOut.add("alert " + alert.level() + " " + alert.title() + " " + alert.mentions());
                return true;
            },
            booked -> carriedOut.add("told " + booked.person() + " " + booked.days()));

    /** A payment steward booked, as the bot is told of it. */
    private static final BotRequest.PaymentBooked BOOKED = new BotRequest.PaymentBooked(
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            DiscordId.of("400000000000000002"),
            "NT-7",
            30,
            0,
            false,
            300,
            Instant.parse("2026-10-02T00:00:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"));

    private static Request<BotRequest> row(final BotRequest payload, final eu.nordtal.s2.common.id.Actor actor) {
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
        final Outcome outcome = subject.handle(row(payload, eu.nordtal.s2.common.id.Actor.person(ADMIN)));
        return Json.encode(assertInstanceOf(Outcome.Done.class, outcome).answer());
    }

    @Test
    void eachKindReachesItsOwnEffect() {
        final DiscordId someone = DiscordId.of("400000000000000002");
        answer(new BotRequest.Grant(someone, 30));
        answer(new BotRequest.Revoke(someone));
        answer(new BotRequest.Unlink(someone));
        answer(BOOKED);
        answer(new BotRequest.SetPlaytime(someone, 7200));

        assertEquals(
                List.of(
                        "grant 400000000000000002 30 by PERSON 400000000000000001",
                        "revoke 400000000000000002 by PERSON 400000000000000001",
                        "unlink 400000000000000002 by PERSON 400000000000000001",
                        "told 400000000000000002 30",
                        "playtime 400000000000000002 7200 by PERSON 400000000000000001"),
                carriedOut);
    }

    @Test
    void theAnswerIsAFlatObjectOfStringsASurfaceCanRead() {
        final DiscordId someone = DiscordId.of("400000000000000002");
        assertEquals("{\"until\":\"2026-10-20T00:00:00Z\"}", answer(new BotRequest.Grant(someone, 30)));
        assertEquals("{\"revoked\":\"2\"}", answer(new BotRequest.Revoke(someone)));
        assertEquals("{\"told\":\"400000000000000002\"}", answer(BOOKED));
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
    void anAnnouncementPostsEveryLanguageAndSaysWhichWentOut() {
        final java.util.Map<String, String> texts = new java.util.LinkedHashMap<>();
        texts.put("de", "Hallo");
        texts.put("fr", "Bonjour");

        final String answer = answer(new BotRequest.Announce(texts));

        assertEquals(List.of("announce de Hallo", "announce fr Bonjour"), carriedOut);
        assertEquals("{\"de\":\"POSTED\",\"fr\":\"NOT_POSTED\"}", answer);
    }

    @Test
    void anAlertIsPostedWithItsMentionsAndSaysItWentOut() {
        final String answer = answer(new BotRequest.PostAlert(Alert.Level.WARN, "Disk 91 %", "", List.of(ADMIN)));

        assertEquals(List.of("alert WARN Disk 91 % [400000000000000001]"), carriedOut);
        assertEquals("{\"posted\":\"true\"}", answer);
    }

    @Test
    void aRowStewardAskedForIsFiledAsTheSystem() {
        subject.handle(
                row(new BotRequest.Revoke(DiscordId.of("400000000000000002")), eu.nordtal.s2.common.id.Actor.STEWARD));

        // Steward is an actor kind of its own, never a made-up name in the id column.
        assertEquals(List.of("revoke 400000000000000002 by STEWARD null"), carriedOut);
    }

    private static String filed(final Actor by) {
        return by.filed().kind() + " " + by.filed().id();
    }
}
