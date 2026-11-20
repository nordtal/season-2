package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WheelTurnTest {

    private final List<String> heard = new ArrayList<>();
    private final WheelTurn turn = new WheelTurn();

    WheelTurnTest() {
        turn.spent(() -> heard.add("refund"));
        turn.drawn(() -> heard.add("pay"), () -> heard.add("store"));
        turn.clicks(() -> heard.add("stop clicks"));
    }

    @Test
    void closingMidTurnStopsTheClicksThenCheersAndPays() {
        turn.end(true, () -> heard.add("cheer"));

        assertEquals(List.of("stop clicks", "cheer", "pay"), heard);
    }

    @Test
    void theWheelComingToRestAfterAClosePaysNothingMore() {
        turn.end(true, () -> heard.add("cheer"));
        turn.end(true, () -> heard.add("cheer"));

        assertEquals(List.of("stop clicks", "cheer", "pay"), heard, "the prize is paid once");
    }

    @Test
    void aDisconnectAfterTheDrawStoresThePrizeWithoutARefund() {
        turn.end(false);
        turn.end(true, () -> heard.add("cheer"));

        assertEquals(List.of("stop clicks", "store"), heard);
    }

    @Test
    void anUnreadablePrizeRefundsTheSpin() {
        final WheelTurn unreadable = new WheelTurn();
        unreadable.spent(() -> heard.add("refund"));

        unreadable.end(false);
        unreadable.end(false);

        assertEquals(List.of("refund"), heard);
    }

    @Test
    void aSoundFailureAfterTheDrawStillPays() {
        assertThrows(
                IllegalStateException.class,
                () -> turn.end(true, () -> {
                    throw new IllegalStateException("sound failed");
                }));
        turn.end(true);

        assertEquals(List.of("stop clicks", "pay"), heard);
    }
}
