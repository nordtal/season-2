package eu.nordtal.s2.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every way of saying "the whole network" is the whole network, never a run that names a blank service. */
class UpdateScopeTextTest {

    @Test
    void namingServicesKeepsTheirOrder() {
        assertEquals(List.of("smp", "limbo"), JdbiUpdateDirectory.cleaned(List.of("smp", "limbo")));
    }

    @Test
    void everyWayOfSayingNothingMeansTheWholeNetwork() {
        final List<List<String>> everyWay = Arrays.asList(null, List.of(), List.of("", "  "));
        for (final List<String> nothing : everyWay) {
            assertEquals(List.of(), JdbiUpdateDirectory.cleaned(nothing));
        }
    }

    @Test
    void blanksAndRepeatsAreDroppedRatherThanWritten() {
        assertEquals(List.of("smp"), JdbiUpdateDirectory.cleaned(Arrays.asList(" smp ", null, "", "smp")));
    }
}
