package eu.nordtal.season.steward.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.internalapi.BankWire;
import eu.nordtal.season.internalapi.InternalClient;
import eu.nordtal.season.internalapi.InternalServer;
import io.javalin.Javalin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BankTest {

    private static final String TOKEN = "bank-token";

    private static final String REFUSAL = "The amount is above the limit for a bunq.me request.";

    private static Javalin bunq;
    private static Bank bank;

    @BeforeAll
    static void startAStandIn() {
        bunq = new InternalServer(BankWire.SERVICE, Map.of("NORDTAL_STEWARD_BUNQ_TOKEN", TOKEN)::get)
                .start(0, config -> {
                    config.routes.post(BankWire.TABS, ctx -> ctx.status(502).json(new BankWire.Refused(400, REFUSAL)));
                    config.routes.post(BankWire.CANCEL, ctx -> ctx.status(502).result("already paid"));
                    config.routes.get(
                            BankWire.RECENT,
                            ctx -> ctx.json(List.of(new BankWire.Payment(
                                    41L, 1205, "2026-09-30T18:04:05Z", "NT-ABC123 " + ctx.queryParam("count")))));
                });
        bank = new Bank(
                new InternalClient(BankWire.SERVICE, "http://127.0.0.1:" + bunq.port(), TOKEN, Duration.ofSeconds(5)));
    }

    @AfterAll
    static void stop() {
        bunq.stop();
    }

    @Test
    void aRefusedTabNamesBunqsRefusalAndCarriesItsWords() {
        final Bank.TabRefused refused = assertThrows(Bank.TabRefused.class, () -> bank.createTab(500, "x"));
        assertEquals(TabRefusal.BAD_REQUEST, refused.refusal());
        assertEquals(REFUSAL, refused.getMessage());
    }

    @Test
    void aStewardBunqThatDoesNotAnswerIsUnreachable() {
        final Bank nobody =
                new Bank(new InternalClient(BankWire.SERVICE, "http://127.0.0.1:1", TOKEN, Duration.ofSeconds(5)));
        assertEquals(
                TabRefusal.UNREACHABLE,
                assertThrows(Bank.TabRefused.class, () -> nobody.createTab(500, "x"))
                        .refusal());
    }

    @Test
    void aTabThatCouldNotBeCancelledIsNoException() {
        assertFalse(bank.cancelTab(7L), "the expiry sweep goes on to the next request");
    }

    @Test
    void paymentsArriveAsTheyLeft() {
        assertEquals(
                List.of(new BankWire.Payment(41L, 1205, "2026-09-30T18:04:05Z", "NT-ABC123 50")),
                bank.recentPayments(50));
    }
}
