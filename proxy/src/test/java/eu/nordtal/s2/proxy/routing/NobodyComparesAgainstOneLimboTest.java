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
 *
 * <h2>The second rule is the root of the first, and it is the one that caught a real miss</h2>
 * The shape rule above looks for the comparison. It found four places and it missed a fifth,
 * {@code RouteIntents}, because that class never wrote the comparison: it was handed
 * {@code gateConfig.serverLimbo()} at construction and kept it in a field called
 * {@code waitingRoom}. The consequence was the worst one available - an evacuation into
 * {@code limbo-standby} refused for every non-admin on the network, by the class whose whole job is
 * to make routing safe - and no regex over the call site could have seen it.
 *
 * <p>So the second rule is on the source rather than on the use: <b>{@code GateSpec#serverLimbo()}
 * is read by {@link PhaseServers} and by the validation that checks it is not blank, and by nothing
 * else.</b> One name cannot spread if only one class may ask for it.</p>
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
        final List<String> offences = new ArrayList<>();
        for (final Path file : proxySources()) {
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

        assertTrue(offences.isEmpty(),
                "these lines ask whether a backend is THE waiting room, and since season-2-ops/120"
                        + " there are two. A player on 'limbo-standby' is, to each of them, somebody"
                        + " on an unrelated backend - so they are never released and sit there until"
                        + " they give up. Use PhaseServers#isWaitingRoom instead:"
                        + System.lineSeparator() + String.join(System.lineSeparator(), offences));
    }

    /**
     * {@code serverLimbo()} anywhere, however it is spelled - {@code config.serverLimbo()},
     * {@code gateConfig.serverLimbo()}, a method reference.
     */
    private static final Pattern READS_THE_CONFIG_KEY = Pattern.compile("serverLimbo\\s*\\(");

    /**
     * The two files allowed to ask {@code gate.yml} for the one limbo name, and why.
     *
     * <p>Neither of them can spread it: one turns it into a {@link PhaseServers}, which is the
     * object every other class is given, and the other only asks whether it is blank.</p>
     */
    private static final Map<String, String> MAY_READ_THE_CONFIG_KEY = Map.of(
            "proxy/src/main/java/eu/nordtal/s2/proxy/routing/PhaseServers.java",
            "builds the object everybody else is handed",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/Configs.java",
            "refuses a blank name at load, which is a check and not a use",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/GateSpec.java",
            "declares the key");

    @Test
    @DisplayName("only PhaseServers reads gate.yml's single limbo name out of the config")
    void onlyPhaseServersReadsTheConfigKey() throws IOException {
        final List<String> offences = new ArrayList<>();
        for (final Path file : proxySources()) {
            final String relative = repositoryRoot().relativize(file).toString();
            if (MAY_READ_THE_CONFIG_KEY.containsKey(relative)) {
                continue;
            }
            final Matcher matcher = READS_THE_CONFIG_KEY.matcher(blankComments(
                    Files.readString(file, StandardCharsets.UTF_8)));
            if (matcher.find()) {
                offences.add(relative);
            }
        }

        assertTrue(offences.isEmpty(),
                "these files take the name of ONE waiting room straight out of gate.yml. That is"
                        + " how RouteIntents ended up refusing every evacuation into"
                        + " 'limbo-standby': not by comparing against one name, but by being"
                        + " handed one and keeping it. Take PhaseServers instead and ask"
                        + " isWaitingRoom:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), offences));
    }

    /**
     * Comments blanked rather than removed, so a match's position is still the position in the
     * file. The same shape the frontend's source rules use, for the same reason: a rule that reads
     * its own explanation as a violation teaches people to stop explaining.
     */
    private static String blankComments(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * Every {@code .java} under {@code proxy/src/main}, {@code src/main/templates} included - the
     * proxy's annotated plugin class lives there, and it is the file that wired the miss.
     */
    private static List<Path> proxySources() throws IOException {
        final Path sources = repositoryRoot().resolve("proxy/src/main");
        assertTrue(Files.isDirectory(sources), sources + " does not exist - if the module moved,"
                + " this path has to move with it, because a missing directory is a check that"
                + " silently stops running");
        try (Stream<Path> tree = Files.walk(sources)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList();
        }
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
