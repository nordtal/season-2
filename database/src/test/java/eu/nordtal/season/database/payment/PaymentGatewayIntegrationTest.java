package eu.nordtal.season.database.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.TestDatabase;
import java.time.Instant;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Holds the one {@code payment_gateway} row: the state steward announces and the watermark it writes once. */
class PaymentGatewayIntegrationTest {

    private Jdbi jdbi;

    @BeforeEach
    void freshDatabase() {
        jdbi = Jdbis.over(TestDatabase.fresh().dataSource());
    }

    @Test
    void noAnnouncementIsUnknownRatherThanOff() {
        assertEquals(PaymentGateway.State.UNKNOWN, PaymentGateway.state(jdbi));
    }

    @Test
    void theLastAnnouncementIsTheState() {
        PaymentGateway.announce(jdbi, true);
        assertEquals(PaymentGateway.State.ON, PaymentGateway.state(jdbi));

        PaymentGateway.announce(jdbi, false);
        assertEquals(PaymentGateway.State.OFF, PaymentGateway.state(jdbi));
    }

    @Test
    void anAnnouncementLeavesTheWatermarkAndTheWatermarkLeavesTheState() {
        final Instant first = Instant.parse("2026-09-01T10:00:00Z");
        Watermark.resolve(jdbi, "", first);
        PaymentGateway.announce(jdbi, true);

        assertEquals(first, Watermark.resolve(jdbi, "", first.plusSeconds(3600)));
        assertEquals(Optional.of(first), Watermark.stored(jdbi));
        assertEquals(PaymentGateway.State.ON, PaymentGateway.state(jdbi));
    }
}
