package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.inbox.BankRequest;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.database.inbox.SmpRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds that the requests settled before their answers became typed keep their arguments, answers and refusals. */
class InboxAnswerUpgradeIntegrationTest {

    private static final DiscordId SOMEONE = DiscordId.of("100000000000000001");

    /** Requests settled before V82, with their answers and refusals as JSON. */
    private static final String SETTLED = """
            INSERT INTO discord_user (discord_id) VALUES ('100000000000000001');
            INSERT INTO bot_inbox (id, kind, payload, actor_kind, status, outcome, finished) VALUES
                (1, 'GRANT', '{"person":"100000000000000001","days":30}', 'STEWARD', 'DONE',
                    '{"until":"2026-11-20T00:00:00Z"}', now()),
                (2, 'REVOKE', '{"person":"100000000000000001"}', 'STEWARD', 'DONE', '{"revoked":"2"}', now()),
                (3, 'UNLINK', '{"person":"100000000000000001"}', 'STEWARD', 'DONE', '{"unlinked":"true"}', now()),
                (4, 'SET_PLAYTIME', '{"person":"100000000000000001","seconds":7200}', 'STEWARD', 'DONE',
                    '{"seconds":"7200"}', now()),
                (5, 'GRANT', '{"person":"100000000000000001","days":7}', 'STEWARD', 'FAILED',
                    '{"error":"the guild said no"}', now()),
                (6, 'POST_ALERT', '{"entry":"00000000-0000-0000-0000-000000000002","mentions":[]}', 'STEWARD', 'DONE',
                    '{"posted":"true"}', now());
            INSERT INTO smp_inbox (id, kind, actor_kind, status, outcome, finished)
                VALUES (1, 'COMPLETE_OBJECTIVE', 'STEWARD', 'REFUSED',
                    '{"reason":"MILESTONE_NOT_ACTIVE","key":"server.milestone-not-active",
                      "args":{"key":"wood","active":"stone"}}', now());
            INSERT INTO smp_complete_objective (request, key) VALUES (1, 'wood');
            INSERT INTO hunger_games_inbox (id, kind, actor_kind, status, outcome, finished)
                VALUES (1, 'START_GAME', 'STEWARD', 'REFUSED',
                    '{"reason":"BELOW_HARD_MINIMUM","key":"server.below-hard-minimum",
                      "args":{"minimum":4,"count":2}}', now());
            INSERT INTO hunger_games_start_game (request, confirmed) VALUES (1, false);
            INSERT INTO payment_request (id, reference, discord_id, days, amount_cents, expires)
                VALUES ('00000000-0000-0000-0000-000000000001', 'NT-AAAAAA', '100000000000000001', 30, 300,
                    now() + interval '1 day');
            INSERT INTO bank_inbox (id, kind, actor_kind, status, outcome, finished) VALUES
                (1, 'OPEN_TAB', 'STEWARD', 'DONE', '{"tab":"4242"}', now()),
                (2, 'CANCEL_TAB', 'STEWARD', 'DONE', '{"cancelled":"4242"}', now()),
                (3, 'CANCEL_TAB', 'STEWARD', 'DONE', '{"nothing":"there is no standing tab"}', now());
            INSERT INTO bank_open_tab (request, payment) VALUES (1, '00000000-0000-0000-0000-000000000001');
            INSERT INTO bank_cancel_tab (request, payment) VALUES
                (2, '00000000-0000-0000-0000-000000000001'),
                (3, '00000000-0000-0000-0000-000000000001');
            """;

    @Test
    void everySettledRequestKeepsItsArgumentsAnswerAndRefusal() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "81");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute(SETTLED));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "82");

        final Inbox<BotRequest> bot = Inbox.over(upgraded.dataSource(), BotRequest.TABLE);
        final Inbox<SmpRequest> smp = Inbox.over(upgraded.dataSource(), SmpRequest.TABLE);
        final Inbox<HungerGamesRequest> games = Inbox.over(upgraded.dataSource(), HungerGamesRequest.TABLE);
        final Inbox<BankRequest> bank = Inbox.over(upgraded.dataSource(), BankRequest.TABLE);
        final Request<BotRequest> failed = bot.find(5).orElseThrow();
        assertAll(
                () -> assertEquals(
                        new BotRequest.Grant(SOMEONE, 30),
                        bot.find(1).orElseThrow().payload()),
                () -> assertEquals(
                        Optional.of(new BotRequest.Grant.Answer(Instant.parse("2026-11-20T00:00:00Z"))),
                        bot.find(1).orElseThrow().answer(BotRequest.Grant.Answer.class)),
                () -> assertEquals(
                        Optional.of(new BotRequest.Revoke.Answer(2)),
                        bot.find(2).orElseThrow().answer(BotRequest.Revoke.Answer.class)),
                () -> assertEquals(
                        Optional.of(new BotRequest.Unlink.Answer(true)),
                        bot.find(3).orElseThrow().answer(BotRequest.Unlink.Answer.class)),
                () -> assertEquals(
                        new BotRequest.SetPlaytime(SOMEONE, 7200),
                        bot.find(4).orElseThrow().payload()),
                () -> assertEquals(InboxStatus.FAILED, failed.status()),
                () -> assertEquals(null, failed.outcome(), "a failure's words are gone"),
                () -> assertEquals(new BotRequest.Grant(SOMEONE, 7), failed.payload()),
                () -> assertEquals(
                        List.of("6 {\"posted\": \"true\"}"),
                        jdbi.withHandle(handle -> handle.createQuery(
                                        "SELECT id || ' ' || outcome FROM bot_inbox WHERE outcome IS NOT NULL")
                                .mapTo(String.class)
                                .list()),
                        "only what an alert answered stays JSON"),
                () -> assertEquals(
                        Optional.of(ServerRefusal.MILESTONE_NOT_ACTIVE.with("wood", "stone")),
                        smp.find(1).orElseThrow().refusal()),
                () -> assertEquals(
                        Optional.of(ServerRefusal.BELOW_HARD_MINIMUM.with(4, 2)),
                        games.find(1).orElseThrow().refusal()),
                () -> assertEquals(
                        List.of(
                                Optional.of(new BankRequest.OpenTab.Answer(4242L)),
                                Optional.of(new BankRequest.CancelTab.Answer(4242L)),
                                Optional.of(new BankRequest.CancelTab.Answer(null))),
                        List.of(
                                bank.find(1).orElseThrow().answer(BankRequest.OpenTab.Answer.class),
                                bank.find(2).orElseThrow().answer(BankRequest.CancelTab.Answer.class),
                                bank.find(3).orElseThrow().answer(BankRequest.CancelTab.Answer.class))));
    }
}
