package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which commands the web interface may ask for: the whole list, pinned.
 *
 * The reasoning per command is not derivable from the declaration, so adding one is a deliberate edit here.
 */
class WebSurfaceTest {

    /** The commands the web interface may ask for, one by one. */
    private static final List<String> ON_THE_WEB = List.of(
            "/announce",
            "/hg start",
            "/smp milestone unlock",
            "/smp objective complete");

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

    @Test
    void aButtonWithNobodyBehindIt() {
        for (final Declaration declaration : Catalogue.all()) {
            if (!declaration.surfaces().contains(Surface.WEB)) {
                continue;
            }
            // WEB commands travel as command_request rows.
            assertTrue(
                    declaration.target() != Target.LOCAL,
                    declaration.name() + " is Target.LOCAL and on the web. Nothing would claim the"
                            + " row: steward-ui writes it and runs no command itself.");
        }
    }

    @Test
    void systemIsNotASource() {
        // Easy to confuse with WEB, and confusing them is a constraint violation at runtime.
        assertTrue(
                Catalogue.all().stream()
                        .filter(declaration -> declaration.surfaces().contains(Surface.SYSTEM))
                        .allMatch(declaration -> declaration.target() != Target.LOCAL),
                "a SYSTEM command has to travel, or nothing would ever run it");
    }
}
