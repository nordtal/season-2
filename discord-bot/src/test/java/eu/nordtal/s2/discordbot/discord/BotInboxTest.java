package eu.nordtal.s2.discordbot.discord;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.DatabaseMessages;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.MessagePreview;
import eu.nordtal.s2.database.inbox.Outcome;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Display;
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
    };

    private final BotInbox subject = new BotInbox(
            effects,
            (tag, message) -> {
                carriedOut.add("announce " + tag + " " + message.key());
                return !tag.equals("fr");
            },
            alert -> {
                carriedOut.add("alert " + alert.level() + " " + alert.title().key() + " " + alert.mentions());
                return true;
            },
            booked -> carriedOut.add("told " + booked.person() + " " + booked.days()),
            previewed -> {
                carriedOut.add("preview " + previewed.person() + " "
                        + previewed.preview().text());
                return !previewed.person().equals(CLOSED);
            });

    /** An admin whose direct messages are closed, so Discord delivers nothing to them. */
    private static final DiscordId CLOSED = DiscordId.of("400000000000000009");

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

    private static Request<BotRequest> row(final BotRequest payload, final Actor actor) {
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
        final Outcome outcome = subject.handle(row(payload, Actor.person(ADMIN)));
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

    @Test
    void aPreviewIsSentToTheAdminAndRefusedWhereDiscordDeliversNothing() {
        final MessagePreview preview = new MessagePreview(
                DatabaseMessages.MESSAGES.announcement().words("Hallo"), "de", "**{text}**", Display.DISCORD_MESSAGE);

        final Outcome sent = subject.handle(row(new BotRequest.PreviewMessage(ADMIN, preview), Actor.person(ADMIN)));
        final Outcome bounced =
                subject.handle(row(new BotRequest.PreviewMessage(CLOSED, preview), Actor.person(CLOSED)));

        assertEquals(new Outcome.Done(null), sent);
        assertEquals(
                ServerRefusal.NOT_DELIVERED,
                assertInstanceOf(Outcome.Refused.class, bounced).refusal().reason());
        assertEquals(
                List.of("preview 400000000000000001 **{text}**", "preview 400000000000000009 **{text}**"), carriedOut);
    }

    @Test
    void anAnnouncementPostsEveryLanguageAndSaysWhichWentOut() {
        final java.util.Map<String, MessageRef> messages = new java.util.LinkedHashMap<>();
        messages.put("de", DatabaseMessages.MESSAGES.announcement().words("Hallo"));
        messages.put("fr", DatabaseMessages.MESSAGES.announcement().words("Bonjour"));

        final String answer = answer(new BotRequest.Announce(messages));

        assertEquals(List.of("announce de announcement.words", "announce fr announcement.words"), carriedOut);
        assertEquals("{\"de\":\"POSTED\",\"fr\":\"NOT_POSTED\"}", answer);
    }

    @Test
    void anAlertIsPostedWithItsMentionsAndSaysItWentOut() {
        final String answer = answer(new BotRequest.PostAlert(
                Alert.Level.WARN, TEXTS.alert().disk(91), List.of(), "https://steward.example/", List.of(ADMIN)));

        assertEquals(List.of("alert WARN alert.disk [400000000000000001]"), carriedOut);
        assertEquals("{\"posted\":\"true\"}", answer);
    }

    @Test
    void aRowStewardAskedForIsFiledAsTheSystem() {
        subject.handle(row(new BotRequest.Revoke(DiscordId.of("400000000000000002")), Actor.STEWARD));

        // Steward is an actor kind of its own, never a made-up name in the id column.
        assertEquals(List.of("revoke 400000000000000002 by STEWARD null"), carriedOut);
    }

    private static String filed(final Actor by) {
        return by.kind() + " " + by.id();
    }
}
