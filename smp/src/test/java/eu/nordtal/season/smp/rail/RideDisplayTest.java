package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RideDisplayTest {

    private static final UUID RIDER = UUID.randomUUID();
    private static final UUID HARBOUR = UUID.randomUUID();
    private static final UUID MINE = UUID.randomUUID();

    private final RideDisplay display = new RideDisplay();

    @Test
    void aRideDepartsOnceAndAReplanToTheSameStationSaysNothing() {
        assertEquals(Optional.of("Harbour"), display.planned(RIDER, HARBOUR, "Harbour", 100));
        display.moved(RIDER, 50);
        assertEquals(Optional.empty(), display.planned(RIDER, HARBOUR, "Harbour", 150));
        final RideDisplay.View view = display.view(RIDER, 0).orElseThrow();
        assertEquals(0.5, view.done(), 1e-9, "the replan moved the bar");
        assertEquals(150, view.metres());
    }

    @Test
    void anotherStationPickedOnTheWayIsANewRide() {
        display.planned(RIDER, HARBOUR, "Harbour", 100);
        display.moved(RIDER, 40);
        assertEquals(Optional.of("Mine"), display.planned(RIDER, MINE, "Mine", 80));
        final RideDisplay.View view = display.view(RIDER, 0).orElseThrow();
        assertEquals(new RideDisplay.View("Mine", 0, 80), view);
    }

    @Test
    void theFullBarIsShownAfterTheArrivalUntilItsTimeIsUp() {
        display.planned(RIDER, HARBOUR, "Harbour", 100);
        display.moved(RIDER, 0.3);
        display.arrived(RIDER, HARBOUR, "Harbour", 1_000);
        // Rides ends every ride after its arrival, which must not take the full bar away.
        display.ended(RIDER);
        assertEquals(Optional.of(new RideDisplay.View("Harbour", 1, 0)), display.view(RIDER, 1_000));
        final long until = 1_000 + RideDisplay.HELD.toMillis();
        assertTrue(display.view(RIDER, until - 1).isPresent());
        assertEquals(Optional.empty(), display.view(RIDER, until));
        assertEquals(Optional.empty(), display.view(RIDER, until - 1), "a gone arrival came back");
    }

    @Test
    void theArrivalNamesTheStationTheCartStoodAtNotTheOneShownBefore() {
        display.planned(RIDER, HARBOUR, "Harbour", 100);
        display.arrived(RIDER, MINE, "Mine", 0);
        assertEquals("Mine", display.view(RIDER, 0).orElseThrow().station());
    }

    @Test
    void aRideAfterAHeldArrivalDepartsAnewEvenToTheSameStation() {
        display.planned(RIDER, HARBOUR, "Harbour", 100);
        display.arrived(RIDER, HARBOUR, "Harbour", 0);
        assertEquals(Optional.of("Harbour"), display.planned(RIDER, HARBOUR, "Harbour", 100));
        assertEquals(0, display.view(RIDER, 0).orElseThrow().done());
    }

    @Test
    void aRideThatEndsBeforeTheStationLeavesTheHud() {
        display.planned(RIDER, HARBOUR, "Harbour", 100);
        display.moved(RIDER, 60);
        display.moved(RIDER, 55);
        display.ended(RIDER);
        assertEquals(Optional.empty(), display.view(RIDER, 0));
    }

    @Test
    void aCharacterTheHudFontLacksIsDrawnAsAQuestionMark() {
        assertEquals("Café Mühle ?", RideDisplay.drawable("Café Mühle 東"));
        assertEquals("Harbour 2", RideDisplay.drawable("Harbour 2"));
    }
}
