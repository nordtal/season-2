package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds what the move of the booking to steward does to a database the bot booked payments in. */
class PaymentBookingUpgradeIntegrationTest {

    @Test
    void whatTheBotLeftUnbookedIsBookedAgainByStewardAndItsNoticesBecomeAlerts() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "12");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO discord_user (discord_id) VALUES ('100000000000000001');
                INSERT INTO payment_request (reference, discord_id, days, amount_cents, expires, bunq_payment_id,
                                             matched_cents, matched_by)
                VALUES ('NT-AAAAAA', '100000000000000001', 30, 300, now() + interval '1 day', 41, 300, 'TAB');
                INSERT INTO payment_notice (bunq_payment_id, reason, detail, posted)
                VALUES (42, 'UNMATCHED', 'nobody claims it', NULL),
                       (43, 'DOUBLE_CLAIM', NULL, now());
                INSERT INTO bot_inbox (kind, payload, actor_kind) VALUES ('SETTLE', '{"reference":"NT-AAAAAA"}', 'STEWARD');
                INSERT INTO setting_override (service, name, path, value, actor_kind, changed)
                VALUES ('discord-bot', 'access', 'tiers', '[{"days":30,"price-cents":400}]', 'STEWARD', now()),
                       ('discord-bot', 'access', 'donation-cents', '700', 'STEWARD', now()),
                       ('discord-bot', 'access', 'guild-id', '"1"', 'STEWARD', now());
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "13");

        assertAll(
                () -> assertEquals(
                        List.of("OPEN null null null"),
                        strings(
                                jdbi,
                                "SELECT status || ' ' || coalesce(bunq_payment_id::text, 'null') || ' '"
                                        + " || coalesce(matched_cents::text, 'null') || ' ' || coalesce(matched_by, 'null')"
                                        + " FROM payment_request"),
                        "a match the old bot never booked is matched again"),
                () -> assertEquals(
                        List.of(
                                "payment:42 PAYMENT nobody claims it unrouted",
                                "payment:43 PAYMENT DOUBLE_CLAIM routed"),
                        strings(
                                jdbi,
                                "SELECT source || ' ' || type || ' ' || detail || ' '"
                                        + " || CASE WHEN routed IS NULL THEN 'unrouted' ELSE 'routed' END"
                                        + " FROM admin_alert ORDER BY source")),
                () -> assertEquals(
                        List.of("false"), strings(jdbi, "SELECT (to_regclass('payment_notice') IS NOT NULL)::text")),
                () -> assertEquals(List.of(), strings(jdbi, "SELECT kind FROM bot_inbox")),
                () -> assertEquals(
                        List.of("discord-bot access guild-id", "network prices donation-cents", "network prices tiers"),
                        strings(
                                jdbi,
                                "SELECT service || ' ' || name || ' ' || path FROM setting_override"
                                        + " ORDER BY service, name, path")));
    }

    private static List<String> strings(final Jdbi jdbi, final String sql) {
        return jdbi.withHandle(
                handle -> handle.createQuery(sql).mapTo(String.class).list());
    }
}
