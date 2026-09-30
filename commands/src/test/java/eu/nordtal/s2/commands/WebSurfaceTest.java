package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which commands the web interface may ask for: the whole list, pinned.
 *
 * The reasoning per command is not derivable from the declaration, so adding one is a deliberate edit here.
 */
class WebSurfaceTest {

    /** The commands the web interface may ask for, one by one. */
    private static final List<String> ON_THE_WEB =
            List.of("/hg start", "/smp milestone unlock", "/smp objective complete");

    @Test
    void nothingElseGrewAButton() {
        assertEquals(
                ON_THE_WEB,
                Catalogue.all().stream()
                        .filter(declaration -> declaration.surfaces().contains(Surface.WEB))
                        .map(Declaration::name)
                        .sorted()
                        .toList(),
                "the set of commands the web interface may ask for changed. If that was meant,"
                        + " change the list above too - and if it was not, a button just appeared"
                        + " in an interface that can stop servers.");
    }
}
