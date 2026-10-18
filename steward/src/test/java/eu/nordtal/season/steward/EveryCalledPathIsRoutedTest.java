package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.database.metric.Metric;
import io.javalin.Javalin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that every {@code /api/...} path the browser asks for is a path this service registers.
 *
 * It does not check whether the answer is right.
 */
class EveryCalledPathIsRoutedTest {

    private static final String FRONTEND = "steward/frontend/src";

    /** An {@code /api/...} literal in the frontend, up to a quote, a query, an interpolation or a backslash. */
    private static final Pattern CALLED = Pattern.compile("[\"`](/api/[^\"`?$\\\\]*)");

    /** The catch-all {@code /api/<path>}. It matches every path, so it does not count as an answer. */
    private static final Pattern CATCH_ALL = Pattern.compile("/api/(?:\\{[^}]+}|<[^>]+>)");

    private static Javalin app;

    @BeforeAll
    static void start() {
        app = RouteTable.start();
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void nothingIsCalledThatIsNotRouted() {
        final Set<String> routes = registered();
        assertTrue(
                routes.size() > 20,
                "only " + routes.size() + " /api routes were found on the running service - the API moved"
                        + " under another prefix, or the service stopped registering before its database.");

        final List<String> unrouted = new ArrayList<>();
        for (final String called : called()) {
            if (routes.stream().noneMatch(route -> matches(route, called))) {
                unrouted.add(called);
            }
        }
        assertEquals(
                List.of(),
                unrouted,
                "a path the frontend calls and this service does not register is a 404 the moment"
                        + " somebody opens that page, and no suite sees it: the frontend's tests mock"
                        + " fetch.");
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

    private static final Pattern ASKED_METRIC = Pattern.compile("useMetrics\\(\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]+)\"");

    /**
     * Checks that every metric curve in the frontend is a metric the sampler writes, named by {@link Metric}.
     *
     * An unknown metric answers 200 with no points, so a misspelt one draws nothing. The test does not check subjects.
     */
    @Test
    void nothingIsDrawnThatIsNeverSampled() {
        final Set<String> written =
                Arrays.stream(Metric.values()).map(Metric::key).collect(Collectors.toCollection(TreeSet::new));

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

    /** Returns whether a route pattern covers a path: {@code {name}} is one segment, {@code <file>} the rest. */
    private static boolean matches(final String route, final String called) {
        // A trailing slash marks where the interpolation began, and only the prefix is known.
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
        return RouteTable.endpoints(app).stream()
                .map(endpoint -> endpoint.path)
                .filter(path ->
                        path.startsWith("/api/") && !CATCH_ALL.matcher(path).matches())
                .collect(Collectors.toCollection(TreeSet::new));
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

    private static void forEachSourceFile(final java.util.function.Consumer<Path> visitor) {
        final Path root = RepositoryRoot.path().resolve(FRONTEND);
        assertTrue(
                Files.isDirectory(root),
                root + " is not there, so this test was reading" + " nothing. Fix the path rather than the assertion.");
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().contains(".test."))
                    .filter(path -> !path.getFileName().toString().contains(".fixtures."))
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
}
