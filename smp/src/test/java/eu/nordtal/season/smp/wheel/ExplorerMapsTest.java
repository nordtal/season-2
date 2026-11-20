package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.function.Function;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class ExplorerMapsTest {

    private final Location origin = new Location(null, 0, 64, 20);
    private final Location centre = new Location(null, 3979, 64, 2188);

    @Test
    void aResultInTheRingIsReplacedByTheResultFromTheBorderCentre() {
        final Location inside = new Location(null, 10239, 32, 0);
        assertEquals(
                inside,
                ExplorerMaps.insideCustomMap(
                        origin,
                        centre,
                        start -> start.equals(origin) ? new Location(null, -1, 32, 20) : inside,
                        Function.identity()));
    }

    @Test
    void aCentreSearchCannotSendThePlayerIntoTheRingEither() {
        assertNull(ExplorerMaps.insideCustomMap(
                origin, centre, start -> new Location(null, 10240, 32, 0), Function.identity()));
    }

    @Test
    void aDestinationInsideTheCustomMapKeepsTheHandoutOrigin() {
        assertEquals(origin, ExplorerMaps.insideCustomMap(origin, centre, Function.identity(), Function.identity()));
    }
}
