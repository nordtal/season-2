package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PassengerEchoesTest {

    private static final int SELF = 42;
    private static final int CART = 7;

    private final PassengerEchoes echoes = new PassengerEchoes();

    @Test
    void theSecondEqualMountOfThePlayerIsAnEcho() {
        assertFalse(echoes.repeats(CART, new int[] {SELF}, SELF), "the first mount reaches the client");
        assertTrue(echoes.repeats(CART, new int[] {SELF}, SELF), "the same mount again would restart the loops");
    }

    @Test
    void aChangedListReachesTheClient() {
        echoes.repeats(CART, new int[] {SELF}, SELF);

        assertFalse(echoes.repeats(CART, new int[] {}, SELF), "the dismount reaches the client");
        assertFalse(echoes.repeats(CART, new int[] {SELF}, SELF), "a mount after a dismount is a new one");
    }

    @Test
    void aVehicleThePlayerDoesNotRideIsLeftAlone() {
        echoes.repeats(CART, new int[] {5}, SELF);

        assertFalse(echoes.repeats(CART, new int[] {5}, SELF), "a bystander's view of another rider is untouched");
    }

    @Test
    void aVehicleSpawnedAgainHearsItsPassengersAgain() {
        echoes.repeats(CART, new int[] {SELF}, SELF);
        echoes.forget(CART);

        assertFalse(echoes.repeats(CART, new int[] {SELF}, SELF));
    }

    @Test
    void aFellowPassengerSpawnedAgainIsSeatedAgain() {
        echoes.repeats(CART, new int[] {SELF, 5}, SELF);
        echoes.forget(5);

        assertFalse(echoes.repeats(CART, new int[] {SELF, 5}, SELF), "the client dropped 5 from the seat with it");
    }

    @Test
    void onlyVehiclesThePlayerRidesAreRemembered() {
        echoes.repeats(CART, new int[] {5}, SELF);
        assertTrue(echoes.empty());

        echoes.repeats(CART, new int[] {SELF}, SELF);
        echoes.repeats(CART, new int[] {}, SELF);
        assertTrue(echoes.empty(), "a dismount leaves nothing behind");
    }

    @Test
    void anotherLevelForgetsEveryVehicle() {
        echoes.repeats(CART, new int[] {SELF}, SELF);
        echoes.forgetAll();

        assertFalse(echoes.repeats(CART, new int[] {SELF}, SELF));
    }

    @Test
    void aPlayerWithoutAnEntityIdYetIsLeftAlone() {
        echoes.repeats(CART, new int[] {-1}, -1);

        assertFalse(echoes.repeats(CART, new int[] {-1}, -1));
    }
}
