package eu.nordtal.s2.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests that writing and reading {@code update_request.scope} are inverses.
 *
 * Every way of saying "the whole network" must read back as the whole network, never as zero services.
 */
class UpdateScopeTextTest {

    /** The pattern the migration's CHECK constraint enforces, character for character. */
    private static final String CHECK = "^[a-z0-9-]+(,[a-z0-9-]+)*$";

    @Test
    void namingServicesSurvivesTheRoundTripInOrder() {
        final String text = JdbiUpdateDirectory.scopeText(List.of("smp", "limbo"));

        assertEquals("smp,limbo", text);
        assertTrue(text.matches(CHECK), "the column's CHECK would refuse this row");
        assertEquals(List.of("smp", "limbo"), JdbiUpdateDirectory.parseScope(text));
    }

    @Test
    void everyWayOfSayingNothingMeansTheWholeNetwork() {
        final List<List<String>> everyWay = Arrays.asList(null, List.of(), List.of("", "  "));
        for (final List<String> nothing : everyWay) {
            assertNull(
                    JdbiUpdateDirectory.scopeText(nothing),
                    "an empty scope was written as a value. A row that names no service is a run"
                            + " that stops nothing while claiming to be scoped - and the column's"
                            + " CHECK would refuse it anyway.");
        }
        assertEquals(List.of(), JdbiUpdateDirectory.parseScope(null));
        assertEquals(List.of(), JdbiUpdateDirectory.parseScope("  "));
    }

    @Test
    void blanksAndRepeatsAreDroppedRatherThanWritten() {
        final String text = JdbiUpdateDirectory.scopeText(Arrays.asList(" smp ", null, "", "smp"));

        assertEquals("smp", text);
        assertTrue(text.matches(CHECK), "the column's CHECK would refuse this row");
    }
}
