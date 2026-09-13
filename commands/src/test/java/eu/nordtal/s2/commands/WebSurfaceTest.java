package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which commands the web interface may ask for - the whole list, pinned.
 *
 * <h2>Why this is a list and not a rule</h2>
 * Till decided on 2026-09-12 which five admin commands get a button in Steward, and the reasoning
 * per command is not derivable from the declaration: {@code /smp aura} is admin-only, irreversible
 * in the same sense and deliberately NOT on the web, because it takes a player who is standing in
 * front of you and a form asking for a name somebody has to type correctly is worse than the chat
 * command that completes it. A rule that produced "every admin command" would be wrong in a way
 * that only shows up as a button nobody meant to build.
 *
 * <p>So the set is written out. Adding {@link Surface#WEB} to a sixth command is then an edit to
 * this test, which is the point: it is a decision, and a decision should cost a line.</p>
 */
class WebSurfaceTest {

    /** The five of concept §10a, by name. */
    private static final List<String> ON_THE_WEB = List.of(
            "/announce",
            "/hg start",
            "/smp farmreset now",
            "/smp milestone unlock",
            "/smp objective complete");

    @Test
    @DisplayName("exactly the five decided commands carry WEB")
    void nothingElseGrewAButton() {
        assertEquals(ON_THE_WEB, Catalogue.all().stream()
                        .filter(declaration -> declaration.surfaces().contains(Surface.WEB))
                        .map(Declaration::name)
                        .sorted()
                        .toList(),
                "the set of commands the web interface may ask for changed. If that was meant,"
                        + " change the list above too - and if it was not, a button just appeared"
                        + " in an interface that can stop servers.");
    }

    @Test
    @DisplayName("a WEB command still has a target that can run it")
    void aButtonWithNobodyBehindIt() {
        for (final Declaration declaration : Catalogue.all()) {
            if (!declaration.surfaces().contains(Surface.WEB)) {
                continue;
            }
            // WEB commands travel as command_request rows, so the target has to be a process with
            // an inbox. Target.LOCAL means "whoever typed it runs it", and nothing in steward-ui
            // runs a Minecraft command - a LOCAL command with a WEB button would be a button that
            // writes a row no process will ever claim.
            assertTrue(declaration.target() != Target.LOCAL,
                    declaration.name() + " is Target.LOCAL and on the web. Nothing would claim the"
                            + " row: steward-ui writes it and runs no command itself.");
        }
    }

    @Test
    @DisplayName("SYSTEM is not a source a row can be written with")
    void systemIsNotASource() {
        // Easy to confuse with WEB, and the consequence of confusing them is a runtime constraint
        // violation rather than a compile error: `announce` declares SYSTEM and its rows are
        // written with source CONSOLE, because nobody typed them. V18 adds WEB to the source CHECK
        // and deliberately does not add SYSTEM.
        assertTrue(Catalogue.all().stream()
                        .filter(declaration -> declaration.surfaces().contains(Surface.SYSTEM))
                        .allMatch(declaration -> declaration.target() != Target.LOCAL),
                "a SYSTEM command has to travel, or nothing would ever run it");
    }
}
