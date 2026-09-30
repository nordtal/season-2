package eu.nordtal.s2.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.proxy.PhaseServers;
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
import org.junit.jupiter.api.Test;

/**
 * Nobody asks "is this the waiting room" by comparing against one name.
 *
 * Only {@link PhaseServers} may read the limbo name, and {@link PhaseServers#isWaitingRoom} asks the question.
 */
class NobodyComparesAgainstOneLimboTest {

    /** {@code something.equals(servers.limbo())}, {@code limbo().equals(something)}, and the {@code ==} spellings. */
    private static final Pattern FORBIDDEN =
            Pattern.compile("\\.equals\\(\\s*[A-Za-z0-9_.()]*servers\\(\\)\\.limbo\\(\\)\\s*\\)"
                    + "|[A-Za-z0-9_.()]*servers\\(\\)\\.limbo\\(\\)\\s*\\.equals\\("
                    + "|==\\s*[A-Za-z0-9_.()]*servers\\(\\)\\.limbo\\(\\)");

    /** File to the reason it is allowed anyway; empty, so an exception is one line. */
    private static final Map<String, String> EXEMPT = Map.of();

    @Test
    void nobodyComparesAgainstTheOneLimbo() throws IOException {
        final List<String> offences = new ArrayList<>();
        for (final Path file : proxySources()) {
            final String relative = repositoryRoot().relativize(file).toString();
            if (EXEMPT.containsKey(relative)) {
                continue;
            }
            final Matcher matcher = FORBIDDEN.matcher(blankComments(Files.readString(file, StandardCharsets.UTF_8)));
            while (matcher.find()) {
                offences.add(relative + ": " + matcher.group().trim());
            }
        }

        assertTrue(
                offences.isEmpty(),
                "these lines ask whether a backend is the waiting room, but there are two. A player"
                        + " on 'limbo-standby' is, to each of them, somebody"
                        + " on an unrelated backend - so they are never released and sit there until"
                        + " they give up. Use PhaseServers#isWaitingRoom instead:"
                        + System.lineSeparator() + String.join(System.lineSeparator(), offences));
    }

    /** {@code serverLimbo()} anywhere, however it is spelled. */
    private static final Pattern READS_THE_CONFIG_KEY = Pattern.compile("serverLimbo\\s*\\(");

    /**
     * The two files allowed to read the limbo name: one builds {@link PhaseServers}, the other checks it is not blank.
     */
    private static final Map<String, String> MAY_READ_THE_CONFIG_KEY = Map.of(
            "proxy/src/main/java/eu/nordtal/s2/proxy/PhaseServers.java",
            "builds the object everybody else is handed",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/ProxySettings.java",
            "refuses a blank name at load, which is a check and not a use",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/GateSpec.java",
            "declares the key");

    @Test
    void onlyPhaseServersReadsTheConfigKey() throws IOException {
        final List<String> offences = new ArrayList<>();
        for (final Path file : proxySources()) {
            final String relative = repositoryRoot().relativize(file).toString();
            if (MAY_READ_THE_CONFIG_KEY.containsKey(relative)) {
                continue;
            }
            final Matcher matcher =
                    READS_THE_CONFIG_KEY.matcher(blankComments(Files.readString(file, StandardCharsets.UTF_8)));
            if (matcher.find()) {
                offences.add(relative);
            }
        }

        assertTrue(
                offences.isEmpty(),
                "these files take the name of ONE waiting room straight out of gate.yml. That is"
                        + " how RouteIntents ended up refusing every evacuation into"
                        + " 'limbo-standby': not by comparing against one name, but by being"
                        + " handed one and keeping it. Take PhaseServers instead and ask"
                        + " isWaitingRoom:" + System.lineSeparator()
                        + String.join(System.lineSeparator(), offences));
    }

    /** Comments blanked rather than removed, so a match's position is still its position in the file. */
    private static String blankComments(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** Every {@code .java} under {@code proxy/src/main}, {@code src/main/templates} included. */
    private static List<Path> proxySources() throws IOException {
        final Path sources = repositoryRoot().resolve("proxy/src/main");
        assertTrue(
                Files.isDirectory(sources),
                sources + " does not exist - if the module moved,"
                        + " this path has to move with it, because a missing directory is a check that"
                        + " silently stops running");
        try (Stream<Path> tree = Files.walk(sources)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
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
