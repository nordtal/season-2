package eu.nordtal.s2.networkcontrol.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Losing the admin rank moves you, it does not only stop you typing.
 *
 * <h2>The half that was missing</h2>
 * The proxy has re-read the admin roster on its poll and its {@code LISTEN} since 2026-09-02, and
 * the reason written next to it is exactly right: "an emergency revocation is precisely the case
 * where [waiting for a disconnect] is the wrong direction". What it fixed was <em>authorisation</em>
 * - who may run {@code /phase} and {@code /smp}. It moved nobody.
 *
 * <p>In {@code MAINTENANCE} the flag is the entire difference between being let onto the backend and
 * being held in the waiting room. So a revoked admin kept standing on the SMP, through a phase whose
 * whole purpose is that nobody is on it, until somebody happened to change the phase. Measured on the
 * local stack 2026-09-07: the change was noticed and logged within four seconds, and the player had
 * not moved three minutes later (finding 141).
 *
 * <p>{@code PlayerRouter#rerouteAll} was already public for this - its javadoc says "a future admin
 * command can force one" - and it re-reads each player's own admission row rather than trusting the
 * phase handed in, so driving it from a flag change is the same pass a phase change makes. The
 * decision to do it at all is the owner's, 2026-09-07, and matches the one the three backends took
 * for the operator grant on 2026-09-04.
 */
class AdminChangeRoutesTest {

    private static final String PLUGIN =
            "network-control/src/main/templates/eu/nordtal/s2/networkcontrol/NetworkControlPlugin.java";
    private static final String ROUTER =
            "network-control/src/main/java/eu/nordtal/s2/networkcontrol/routing/PlayerRouter.java";

    @Test
    @DisplayName("a changed admin flag forces a re-route, not just a roster update")
    void aChangedFlagReroutes() {
        final String body = methodBody(read(PLUGIN), "final Runnable refreshAdmins =");
        assertTrue(body.contains("refreshAdmins(access.admins())"),
                "the roster refresh itself has to stay - it is what makes the flag right for the"
                        + " commands");
        assertTrue(body.contains("rerouteAll("),
                "a changed admin flag has to re-route as well. Refreshing the roster fixes who may"
                        + " type a command; in MAINTENANCE the flag is the whole difference between"
                        + " standing on the SMP and being held, and a revoked admin stayed there"
                        + " until the phase happened to change (finding 141).");
    }

    @Test
    @DisplayName("the re-route runs only when something actually changed")
    void onlyOnAChange() {
        final String body = methodBody(read(PLUGIN), "final Runnable refreshAdmins =");
        final int guard = body.indexOf("if (changed > 0)");
        assertTrue(guard >= 0 && guard < body.indexOf("rerouteAll("),
                "the pass over every connected player has to sit inside the 'changed > 0' guard -"
                        + " this runs on every poll tick, and on an ordinary tick nothing changed");
    }

    @Test
    @DisplayName("rerouteAll is still the shared pass and still re-reads each player")
    void theRouterStillRereads() {
        final String source = read(ROUTER);
        assertTrue(source.contains("public int rerouteAll("),
                "rerouteAll has to stay public - the phase switch, the /phase command and now the"
                        + " admin refresh all drive it");
        assertTrue(source.contains("for logging only"),
                "the phase argument has to stay documented as logging-only: each player's own"
                        + " re-read is what decides, which is why driving this from a flag change"
                        + " needs no phase of its own");
    }

    /** The text from a declaration to the line that closes it at its own indent. */
    private static String methodBody(final String source, final String signature) {
        final int start = source.indexOf(signature);
        if (start < 0) {
            throw new IllegalStateException(signature + " is gone from " + PLUGIN
                    + " - this test names it directly and has to be pointed at its replacement");
        }
        final int end = source.indexOf("\n        };", start);
        if (end < 0) {
            throw new IllegalStateException("no close found for " + signature);
        }
        return source.substring(start, end);
    }

    private static String read(final String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return candidate;
    }
}
