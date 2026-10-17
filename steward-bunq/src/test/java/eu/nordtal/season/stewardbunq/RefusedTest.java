package eu.nordtal.season.stewardbunq;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bunq.sdk.exception.ExceptionFactory;
import eu.nordtal.season.internalapi.BankWire;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What steward is told when bunq or steward-bunq makes no tab: bunq's status, if it answered one, and the words. */
class RefusedTest {

    @Test
    void bunqsRefusalCrossesWithTheStatusBunqAnswered() {
        final BankWire.Refused refused = StewardBunq.refused(
                ExceptionFactory.createExceptionForResponse(400, List.of("Amount too high."), "response-1"));

        assertEquals(400, refused.bunqStatus());
    }

    @Test
    void aMissingKeyAsksNoBunqAndSoCarriesNoStatus() {
        assertEquals(null, StewardBunq.refused(new BunqGateway.NotConfigured()).bunqStatus());
    }
}
