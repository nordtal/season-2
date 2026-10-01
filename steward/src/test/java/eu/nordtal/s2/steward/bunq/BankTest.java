package eu.nordtal.s2.steward.bunq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.s2.internalapi.BankWire;
import eu.nordtal.s2.internalapi.InternalClient;
import eu.nordtal.s2.internalapi.InternalServer;
import io.javalin.Javalin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link Bank} against a stand-in for steward-bunq that refuses tabs the way bunq does. */
class BankTest {

    private static final String TOKEN = "bank-token";

    /** What bunq says when it refuses a tab, which the person waiting is shown unchanged. */
    private static final String REFUSAL = "The amount is above the limit for a bunq.me request.";

    private static Javalin bunq;
    private static Bank bank;

    @BeforeAll
    static void startAStandIn() {
        bunq = new InternalServer(BankWire.SERVICE, Map.of("NORDTAL_STEWARD_BUNQ_TOKEN", TOKEN)::get)
                .start(0, config -> {
                    config.routes.post(BankWire.TABS, ctx -> ctx.status(502).result(REFUSAL));
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
    void aRefusedTabCarriesBunqsOwnWords() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> bank.createTab(500, "x"));
        assertEquals(REFUSAL, refused.getMessage());
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
