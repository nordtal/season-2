package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WheelTurnTest {

    private final List<String> heard = new ArrayList<>();
    private final WheelTurn turn = new WheelTurn(() -> heard.add("cheer"), () -> heard.add("pay"));

    WheelTurnTest() {
        turn.clicks(() -> heard.add("stop clicks"));
    }

    @Test
    void closingMidTurnStopsTheClicksThenCheersAndPays() {
        turn.end(true);

        assertEquals(List.of("stop clicks", "cheer", "pay"), heard);
    }

    @Test
    void theWheelComingToRestAfterAClosePaysNothingMore() {
        turn.end(true);
        turn.end(true);

        assertEquals(List.of("stop clicks", "cheer", "pay"), heard, "the prize is paid once");
    }

    @Test
    void aPlayerWhoLeftIsPaidWithoutTheSound() {
        turn.end(false);
        turn.end(true);

        assertEquals(List.of("stop clicks", "pay"), heard);
    }
}
