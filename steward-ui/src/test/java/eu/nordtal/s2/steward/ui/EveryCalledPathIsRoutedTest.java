package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every {@code /api/...} path the browser asks for is a path this service answers.
 *
 * A feature built in two halves, each tested and each green, can still miss the middle: the piece
 * between the worker and the frontend grows nothing, and every suite stays green while the page
 * answers 404. This only checks that the path is registered at all, not that the answer is right.
 */
class EveryCalledPathIsRoutedTest {

    private static final String FRONTEND = "steward-ui/frontend/src";
    private static final String ROUTES = "steward-ui/src/main/java/eu/nordtal/s2/steward/ui/StewardUi.java";
    private static final String SAMPLER =
            "steward-worker/src/main/java/eu/nordtal/s2/steward/worker/metric/Sampler.java";

    /**
     * An {@code /api/...} literal in the frontend, in either quote style.
     *
     * It stops at the first character that ends a path: the closing quote, a {@code ?} beginning
     * a query, a {@code $} beginning an interpolation - the interpolated part is a value and not
     * a route segment, and what matters for routing is the shape up to there - or a backslash,
     * which begins an escape.
     *
     * A backslash cannot occur in a path, so it belongs with the other three terminators: without
     * it, a template literal containing {@code \n} would have its escape read as part of the path.
     */
    private static final Pattern CALLED = Pattern.compile("[\"`](/api/[^\"`?$\\\\]*)");

    /** {@code cfg.routes.get("/api/...", ...)} and the other five verbs, plus sse. */
    private static final Pattern REGISTERED =
            Pattern.compile("routes\\.(?:get|post|put|patch|delete|sse)\\(\\s*\"(/api/[^\"]*)\"");

    /**
     * The catch-all, which is a route and is not an answer.
     *
     * {@code /api/<path>} is registered last on purpose: it turns an unmatched {@code /api}
     * request into a plain 404 instead of letting the single-page fallback claim it. It therefore
     * matches every path ever written, and counting it would make this whole test say nothing.
     */
    private static final Pattern CATCH_ALL = Pattern.compile("/api/(?:\\{[^}]+}|<[^>]+>)");

    @Test
    void nothingIsCalledThatIsNotRouted() {
        final Set<String> routes = registered();
        // A guard that silently stops guarding is worse than none: say so here, not at the wrong assertion.
        assertTrue(
                routes.size() > 20,
                "only " + routes.size() + " routes were read out of " + ROUTES
                        + " - the route table moved or the pattern stopped matching it.");

        final List<String> unrouted = new ArrayList<>();
        for (final String called : called()) {
            if (routes.stream().noneMatch(route -> matches(route, called))) {
                unrouted.add(called);
            }
        }
        assertEquals(
                List.of(),
                unrouted,
                "the browser can only reach steward-ui. A path the frontend calls and this service"
                        + " does not register is a 404 the moment somebody opens that page, and no"
                        + " suite sees it: the worker's tests call the worker, and the frontend's"
                        + " tests mock fetch. See this class's javadoc for the one that got out.");
    }

    @Test
    void theFrontendIsActuallyRead() {
        final Set<String> called = called();
        assertTrue(
                called.size() > 15,
                "only " + called.size() + " /api paths were found in " + FRONTEND
                        + " - the tree moved, or the literals are written some other way now.");
        assertFalse(
                registered().contains("/api/<path>"),
                "the catch-all is a route that answers 404 and matches every path there is."
                        + " Counting it makes this test vacuous - see CATCH_ALL's javadoc.");
        assertTrue(
                called.contains("/api/messages"),
                "/api/messages is the path this test was written for; if it is gone, so is the"
                        + " reason to look for it, and this line should go with it.");
    }

    /** {@code useMetrics("host", "cpu_percent", 6)} - the second argument is the one that matters. */
    private static final Pattern ASKED_METRIC = Pattern.compile("useMetrics\\(\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]+)\"");

    /** {@code new MetricSample(subject, "cpu_percent", at, value)} in the sampler. */
    private static final Pattern WRITTEN_METRIC = Pattern.compile("new MetricSample\\([^,]*,\\s*\"([^\"]+)\"");

    /**
     * Every metric the frontend draws a curve of is one the sampler actually writes.
     *
     * The same mistake one layer in
     * The test above holds the paths, and it was green while the start page's CPU sparkline had
     * never drawn a single point in its life: the path {@code /api/metrics} is registered, so
     * nothing complained. What was wrong is the value of {@code metric} - the page asked for
     * {@code cpu} and the sampler writes {@code cpu_percent}, and
     * {@link StewardUi}'s handler answers an unknown name with {@code 200} and an empty list of
     * points rather than with an error. That is right for a name with no samples yet, on a
     * deployment where the sampler has not run; it is indistinguishable from a name that will
     * never have any. Measured on the running stack: thousands of rows under
     * {@code host/cpu_percent}, none at all under {@code host/cpu}.
     *
     * Only the metric is held, never the subject. The sampler writes {@code "host"} as a
     * literal and every service name as a variable, so a list of valid subjects cannot be read out
     * of it - and the subject was not where this went wrong.
     */
    @Test
    void nothingIsDrawnThatIsNeverSampled() {
        final Set<String> written = literals(WRITTEN_METRIC, repository().resolve(SAMPLER));
        assertTrue(
                written.size() >= 4,
                "only " + written.size() + " metric names were read out of " + SAMPLER
                        + " - the sampler moved or it names its metrics some other way now, and"
                        + " this guard is measuring nothing.");

        final Set<String> asked = new TreeSet<>();
        forEachSourceFile(file -> {
            final Matcher matcher = ASKED_METRIC.matcher(read(file));
            while (matcher.find()) {
                asked.add(matcher.group(1));
            }
        });
        assertFalse(
                asked.isEmpty(),
                "no useMetrics call was found in " + FRONTEND + " - either no page draws a curve"
                        + " any more, in which case this test should go, or the call is written"
                        + " some other way and the pattern stopped seeing it.");

        final List<String> unsampled =
                asked.stream().filter(name -> !written.contains(name)).sorted().toList();
        assertEquals(
                List.of(),
                unsampled,
                "a metric nobody samples is answered with 200 and an empty list, so the curve is"
                        + " simply never there and no error is reported anywhere. The names the"
                        + " sampler writes are " + written + ".");
    }

    /**
     * Whether a Javalin route pattern covers a called path.
     *
     * Both parameter styles are here because this file uses both, and they differ in exactly the
     * way that matters: {@code {name}} is one segment, {@code <file>} is the rest of the path,
     * slashes included. That is why a config file called {@code smp/config.yml} can be one
     * parameter at all.
     */
    private static boolean matches(final String route, final String called) {
        // A trailing slash is where the interpolation began, not part of the path; only the prefix is known.
        if (called.endsWith("/")) {
            return route.startsWith(called);
        }

        final StringBuilder regex = new StringBuilder();
        final Matcher parameter = Pattern.compile("\\{[^}]+}|<[^>]+>").matcher(route);
        int at = 0;
        while (parameter.find()) {
            regex.append(Pattern.quote(route.substring(at, parameter.start())));
            regex.append(parameter.group().startsWith("{") ? "[^/]+" : ".+");
            at = parameter.end();
        }
        regex.append(Pattern.quote(route.substring(at)));
        return called.matches(regex.toString());
    }

    private static Set<String> registered() {
        final Set<String> routes = new TreeSet<>();
        final Matcher matcher = REGISTERED.matcher(read(repository().resolve(ROUTES)));
        while (matcher.find()) {
            if (!CATCH_ALL.matcher(matcher.group(1)).matches()) {
                routes.add(matcher.group(1));
            }
        }
        return routes;
    }

    private static Set<String> called() {
        final Set<String> paths = new TreeSet<>();
        forEachSourceFile(file -> {
            final Matcher matcher = CALLED.matcher(read(file));
            while (matcher.find()) {
                paths.add(matcher.group(1));
            }
        });
        return paths;
    }

    private static Set<String> literals(final Pattern pattern, final Path file) {
        final Set<String> found = new TreeSet<>();
        final Matcher matcher = pattern.matcher(read(file));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * Every {@code .ts} and {@code .tsx} source file of the frontend, handed over one at a time.
     *
     * Two guards in this class walk the same tree looking for two different literals; the walk
     * belongs in one place rather than in both.
     */
    private static void forEachSourceFile(final java.util.function.Consumer<Path> visitor) {
        final Path root = repository().resolve(FRONTEND);
        assertTrue(
                Files.isDirectory(root),
                root + " is not there, so this test was reading" + " nothing. Fix the path rather than the assertion.");
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    // A test's fixture is not a call the browser makes, and a mock may name a path that never existed.
                    .filter(path -> !path.getFileName().toString().contains(".test."))
                    .filter(path -> {
                        final String name = path.getFileName().toString();
                        return name.endsWith(".ts") || name.endsWith(".tsx");
                    })
                    .forEach(visitor);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(
                directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
