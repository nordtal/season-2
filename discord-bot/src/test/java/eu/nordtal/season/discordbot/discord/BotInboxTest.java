package eu.nordtal.season.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messages.spec.Display;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BotInboxTest {

    private static final DiscordId ADMIN = DiscordId.of("400000000000000001");

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
                carriedOut.add("alert " + alert.entry() + " " + alert.mentions());
                return true;
            },
            booked -> carriedOut.add("told " + booked.payment()),
            previewed -> {
                carriedOut.add("preview " + previewed.person() + " "
                        + previewed.preview().text());
                return !previewed.person().equals(CLOSED);
            },
            previewed -> {
                carriedOut.add("document " + previewed.person() + " "
                        + previewed.preview().language());
                return true;
            });

    /** Discord delivers nothing to this admin, whose direct messages are closed. */
    private static final DiscordId CLOSED = DiscordId.of("400000000000000009");

    private static final BotRequest.PaymentBooked BOOKED = new BotRequest.PaymentBooked(
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            UUID.fromString("00000000-0000-0000-0000-000000000008"));

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
                null,
                null,
                null);
    }

    private String answer(final BotRequest payload) {
        return Json.encode(done(payload));
    }

    private @Nullable Object done(final BotRequest payload) {
        final Outcome outcome = subject.handle(row(payload, Actor.person(ADMIN)));
        return assertInstanceOf(Outcome.Done.class, outcome).answer();
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
                        "told 00000000-0000-0000-0000-000000000007",
                        "playtime 400000000000000002 7200 by PERSON 400000000000000001"),
                carriedOut);
    }

    @Test
    void anAccessChangeAnswersWithItsKindsTypedAnswer() {
        final DiscordId someone = DiscordId.of("400000000000000002");
        assertEquals(
                new BotRequest.Grant.Answer(Instant.parse("2026-10-20T00:00:00Z")),
                done(new BotRequest.Grant(someone, 30)));
        assertEquals(new BotRequest.Revoke.Answer(2), done(new BotRequest.Revoke(someone)));
        assertEquals(new BotRequest.Unlink.Answer(true), done(new BotRequest.Unlink(someone)));
        assertNull(done(BOOKED));
        assertNull(done(new BotRequest.SetPlaytime(someone, 7200)));
    }

    @Test
    void aPreviewIsSentToTheAdminAndRefusedWhereDiscordDeliversNothing() {
        final MessagePreview preview = new MessagePreview(
                DatabaseMessages.MESSAGES.announcement().words("Hallo").key(),
                "de",
                "**{text}**",
                Display.DISCORD_MESSAGE,
                "network",
                Map.of("text", "Hallo"));

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
        final Map<String, String> words = new LinkedHashMap<>();
        words.put("de", "Hallo");
        words.put("fr", "Bonjour");

        final Object answer = done(BotRequest.Announce.words(words));

        assertEquals(List.of("announce de announcement.words", "announce fr announcement.words"), carriedOut);
        assertEquals(
                new BotRequest.Announce.Answer(List.of(
                        new BotRequest.Announce.Posted("de", true), new BotRequest.Announce.Posted("fr", false))),
                answer);
    }

    @Test
    void aMilestoneIsRenderedInEachLanguageFromItsName() {
        final Map<String, String> names = new LinkedHashMap<>();
        names.put("de", "Standbein");
        names.put("en", "Foothold");

        done(BotRequest.Announce.milestone(BotRequest.Announce.Message.MILESTONE_BORDER, "border.1", names));

        assertEquals(
                List.of("announce de announcement.milestone.border", "announce en announcement.milestone.border"),
                carriedOut);
    }

    @Test
    void anAlertIsPostedWithItsMentionsAndSaysItWentOut() {
        final UUID entry = UUID.fromString("00000000-0000-0000-0000-000000000042");
        final String answer = answer(new BotRequest.PostAlert(entry, List.of(ADMIN)));

        assertEquals(List.of("alert " + entry + " [400000000000000001]"), carriedOut);
        assertEquals("{\"posted\":\"true\"}", answer);
    }

    @Test
    void aRowStewardAskedForIsFiledAsTheSystem() {
        subject.handle(row(new BotRequest.Revoke(DiscordId.of("400000000000000002")), Actor.STEWARD));

        // Steward is its own actor kind and never a name in the id column.
        assertEquals(List.of("revoke 400000000000000002 by STEWARD null"), carriedOut);
    }

    private static String filed(final Actor by) {
        return by.kind() + " " + by.id();
    }
}
