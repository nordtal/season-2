package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which commands the web interface may ask for - the whole list, pinned.
 *
 * This is a list and not a rule, because the reasoning per command is not derivable from the
 * declaration: {@code /smp aura} is admin-only, irreversible in the same sense and deliberately
 * not on the web, because it takes a player who is standing in front of you and a form asking for
 * a name somebody has to type correctly is worse than the chat command that completes it. A rule
 * that produced "every admin command" would be wrong in a way that only shows up as a button
 * nobody meant to build.
 *
 * So the set is written out. Adding {@link Surface#WEB} to a sixth command is then an edit to
 * this test, which is the point: it is a decision, and a decision should cost a line.
 */
class WebSurfaceTest {

    /**
     * The commands the web interface may ask for, one by one.
     *
     * The four {@code /phase} commands arrived from the opposite direction to the others: they
     * were pushed off chat and Discord and had to land somewhere, and a WEB row is pinned to the
     * asker's Discord id where a CONSOLE row carries no identity at all.
     *
     * {@code /access settle} and {@code /access unlink} are here because writing {@code /access}
     * commands is off Discord and these two have nowhere else to go: granting and revoking are
     * routes of the interface's own, while settling needs the bot's JDA and unlinking its tables.
     * Neither takes somebody standing in front of you, and neither is typed - the reference comes
     * from {@code GET /api/payments/open} and the member from {@code /api/people}, both
     * {@link Argument.Kind} values the interface draws as a list.
     */
    private static final List<String> ON_THE_WEB = List.of(
            "/access settle",
            "/access unlink",
            "/announce",
            "/hg start",
            "/phase launch",
            "/phase set",
            "/phase show",
            "/phase smp-start",
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
        // Easy to confuse with WEB, and the consequence of confusing them is a runtime constraint violation rather.
        assertTrue(
                Catalogue.all().stream()
                        .filter(declaration -> declaration.surfaces().contains(Surface.SYSTEM))
                        .allMatch(declaration -> declaration.target() != Target.LOCAL),
                "a SYSTEM command has to travel, or nothing would ever run it");
    }
}
