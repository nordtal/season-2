package eu.nordtal.s2.proxy.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nobody asks "is this the waiting room" by comparing against one name (season-2-ops/120).
 *
 * <p>This is the guard the ticket asked for in its own words: <em>half built is worse here than not
 * built.</em> Adding {@code limbo-standby} is easy; what makes it correct is that every place that
 * decides "is this player waiting" accepts both names. There were four such places, they were found
 * by reading, and reading is not a thing that repeats itself - the next one gets written by somebody
 * who has never heard of a standby, and it will look exactly like the four did.</p>
 *
 * <p>So the rule is on the shape rather than on the outcome: {@code x.equals(servers.limbo())} and
 * its inverse are forbidden in this module's sources. {@link PhaseServers#isWaitingRoom} is the one
 * way to ask, and it is short enough that there is no excuse.</p>
 *
 * <p><b>A failure here is not a style complaint.</b> It means a player parked on the standby during
 * a swap is invisible to whatever that line belongs to - and the way that shows up in the world is
 * somebody sitting in a waiting room that never lets them out.</p>
 */
class NobodyComparesAgainstOneLimboTest {

    /**
     * {@code something.equals(servers.limbo())}, {@code limbo().equals(something)}, and the
     * {@code ==} spellings. Deliberately broad: a near-miss that this does not catch is a near-miss
     * nobody catches.
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\.equals\\(\\s*[A-Za-z0-9_.()]*servers\\(\\)\\.limbo\\(\\)\\s*\\)"
                    + "|[A-Za-z0-9_.()]*servers\\(\\)\\.limbo\\(\\)\\s*\\.equals\\("
                    + "|==\\s*[A-Za-z0-9_.()]*servers\\(\\)\\.limbo\\(\\)");

    /**
     * File -> why it is allowed to say it anyway. Empty, and typed rather than deleted, the same
     * convention the frontend's source rules use: the next exception is a line, not a decision
     * about whether exceptions exist.
     */
    private static final Map<String, String> EXEMPT = Map.of();

    @Test
    @DisplayName("no proxy source decides 'is this the waiting room' against one name")
    void nobodyComparesAgainstTheOneLimbo() throws IOException {
        final Path sources = repositoryRoot().resolve("proxy/src/main");
        assertTrue(Files.isDirectory(sources), sources + " does not exist - if the module moved,"
                + " this path has to move with it, because a missing directory is a check that"
                + " silently stops running");

        final List<String> offences = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(sources)) {
            for (final Path file : tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                final String relative = repositoryRoot().relativize(file).toString();
                if (EXEMPT.containsKey(relative)) {
                    continue;
                }
                final Matcher matcher = FORBIDDEN.matcher(blankComments(
                        Files.readString(file, StandardCharsets.UTF_8)));
                while (matcher.find()) {
                    offences.add(relative + ": " + matcher.group().trim());
                }
            }
        }

        assertTrue(offences.isEmpty(),
                "these lines ask whether a backend is THE waiting room, and since season-2-ops/120"
                        + " there are two. A player on 'limbo-standby' is, to each of them, somebody"
                        + " on an unrelated backend - so they are never released and sit there until"
                        + " they give up. Use PhaseServers#isWaitingRoom instead:"
                        + System.lineSeparator() + String.join(System.lineSeparator(), offences));
    }

    /**
     * Comments blanked rather than removed, so a match's position is still the position in the
     * file. The same shape the frontend's source rules use, for the same reason: a rule that reads
     * its own explanation as a violation teaches people to stop explaining.
     */
    private static String blankComments(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
