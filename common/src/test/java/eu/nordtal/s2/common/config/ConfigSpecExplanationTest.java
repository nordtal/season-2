package eu.nordtal.s2.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Checks that every ordered {@code @ConfigSpec} property carries {@code @Explain} or {@code @NoExplanationNeeded}.
 *
 * Reads the sources as text, since {@code :common} depends on none of the modules that own the specs. Every
 * file walked must be declared through {@code repositoryRootTestInputs}, or the test stays up to date.
 */
class ConfigSpecExplanationTest {

    /** The spec files the walk must find at least, so a renamed module cannot make it find nothing. */
    private static final Set<String> KNOWN = Set.of(
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/config/AccessSpec.java",
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/config/BotSpec.java",
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/config/DatabaseSpec.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/config/ColoursSpec.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/config/DatabaseSpec.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/config/HungerGamesSpec.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/config/SoundsSpec.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/config/ColoursSpec.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/config/DatabaseSpec.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/config/LimboSpec.java",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/ColoursSpec.java",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/DatabaseSpec.java",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/GateSpec.java",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/NetworkSpec.java",
            "proxy/src/main/java/eu/nordtal/s2/proxy/config/PackSpec.java",
            "smp/src/main/java/eu/nordtal/s2/smp/config/ColoursSpec.java",
            "smp/src/main/java/eu/nordtal/s2/smp/config/DatabaseSpec.java",
            "smp/src/main/java/eu/nordtal/s2/smp/config/MilestonesSpec.java",
            "smp/src/main/java/eu/nordtal/s2/smp/config/PrestigeSpec.java",
            "smp/src/main/java/eu/nordtal/s2/smp/config/SmpSpec.java",
            "smp/src/main/java/eu/nordtal/s2/smp/config/SoundsSpec.java",
            "steward-ui/src/main/java/eu/nordtal/s2/steward/ui/config/DatabaseSpec.java",
            "steward-ui/src/main/java/eu/nordtal/s2/steward/ui/config/UiSpec.java",
            "steward-worker/src/main/java/eu/nordtal/s2/steward/worker/config/DatabaseSpec.java",
            "steward-worker/src/main/java/eu/nordtal/s2/steward/worker/config/StewardSpec.java");

    private static final Pattern ORDER = Pattern.compile("@Order\\(");
    private static final Pattern KEY = Pattern.compile("@Key\\(\"([^\"]*)\"\\)");
    private static final Pattern EXPLAIN = Pattern.compile("@Explain\\(");
    private static final Pattern NO_EXPLANATION_NEEDED = Pattern.compile("@NoExplanationNeeded\\b");
    private static final Pattern NAME = Pattern.compile("@Name\\(\"[^\"]+\"\\)");

    @Test
    void theWalkStillFindsEveryConfigSpecFileItFoundWhenThisTestWasWritten() {
        final Set<String> found =
                specFiles().stream().map(RepositoryRoot::relative).collect(Collectors.toCollection(TreeSet::new));
        assertTrue(
                found.containsAll(KNOWN),
                "the walk does not find every known config spec file. Missing: " + missing(found));
    }

    @Test
    void everyOrderPropertyCarriesExplainOrNoexplanationneededNeverNeither() {
        final Map<String, List<String>> unexplained = new TreeMap<>();
        for (final Path file : specFiles()) {
            final String name = RepositoryRoot.relative(file);
            final String text = read(file);
            for (final String block : blocks(text)) {
                if (!EXPLAIN.matcher(block).find()
                        && !NO_EXPLANATION_NEEDED.matcher(block).find()) {
                    unexplained
                            .computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(settingName(block));
                }
            }
        }
        assertEquals(
                Map.of(),
                unexplained,
                "every @ConfigSpec property with @Order needs either @Explain(\"...\") - the short"
                        + " sentence shown next to the setting in the Steward UI - or @NoExplanationNeeded,"
                        + " a deliberate decision that the name and its allowed values already say enough."
                        + " Listed above by file and setting key.");
    }

    @Test
    void everyOrderPropertyCarriesNameTheNameTheStewardUiShowsInsteadOfTheKey() {
        final Map<String, List<String>> unnamed = new TreeMap<>();
        for (final Path file : specFiles()) {
            final String name = RepositoryRoot.relative(file);
            for (final String block : blocks(read(file))) {
                if (!NAME.matcher(block).find()) {
                    unnamed.computeIfAbsent(name, ignored -> new ArrayList<>()).add(settingName(block));
                }
            }
        }
        assertEquals(
                Map.of(),
                unnamed,
                "every @ConfigSpec property with @Order needs @Name(\"...\") - the Steward UI shows it"
                        + " instead of the key, units in brackets: \"Poll interval (seconds)\"."
                        + " Listed above by file and setting key.");
    }

    @Test
    void noPropertyCarriesBothExplainAndNoexplanationneeded() {
        final Map<String, List<String>> contradictory = new TreeMap<>();
        for (final Path file : specFiles()) {
            final String name = RepositoryRoot.relative(file);
            final String text = read(file);
            for (final String block : blocks(text)) {
                if (EXPLAIN.matcher(block).find()
                        && NO_EXPLANATION_NEEDED.matcher(block).find()) {
                    contradictory
                            .computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(settingName(block));
                }
            }
        }
        assertEquals(
                Map.of(),
                contradictory,
                "a property cannot both need a short sentence and be declared self-evident - jcore's"
                        + " schema writer refuses this combination at runtime; here it is caught by name.");
    }

    /** One text slice per {@code @Order(...)} occurrence, from that annotation to the next one. */
    private static List<String> blocks(final String text) {
        final List<Integer> starts = new ArrayList<>();
        final Matcher matcher = ORDER.matcher(text);
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        final List<String> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            final int from = starts.get(i);
            final int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            blocks.add(text.substring(from, to));
        }
        return blocks;
    }

    /** The {@code @Key} value of a block, or a fallback that still names it if one is somehow absent. */
    private static String settingName(final String block) {
        final Matcher key = KEY.matcher(block);
        return key.find()
                ? key.group(1)
                : "(no @Key found in: " + block.strip().lines().findFirst().orElse("?") + ")";
    }

    /**
     * Every {@code *Spec.java} in a {@code config} directory under any module's {@code src/main/java}.
     *
     * Matched by directory name rather than by module list, so a module gaining its first
     * {@code @ConfigSpec} needs nothing added here - the same shape {@code EveryBundleIsCompleteTest}
     * uses for message bundles.
     */
    private static List<Path> specFiles() {
        final List<Path> found = new ArrayList<>();
        for (final Path module : childDirectories(RepositoryRoot.path())) {
            final Path javaRoot = module.resolve("src/main/java");
            if (!Files.isDirectory(javaRoot)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(javaRoot)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith("Spec.java"))
                        .filter(p -> p.getParent() != null
                                && "config".equals(p.getParent().getFileName().toString()))
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + javaRoot, e);
            }
        }
        found.sort(Path::compareTo);
        return found;
    }

    private static List<Path> childDirectories(final Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    private static Set<String> missing(final Set<String> found) {
        final Set<String> missing = new TreeSet<>(KNOWN);
        missing.removeAll(found);
        return missing;
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
