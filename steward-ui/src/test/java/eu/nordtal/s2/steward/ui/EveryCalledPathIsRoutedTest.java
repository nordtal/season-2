package eu.nordtal.s2.steward.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code /api/...} path the browser asks for is a path this service answers.
 *
 * <h2>What went wrong, which is the only reason this exists</h2>
 * steward/48 was built in two halves that were each tested and each green. {@code steward-worker}
 * grew {@code GET|PUT /api/messages}, the frontend grew the card that calls it, and the piece
 * between them - steward-ui, which is the only thing the browser can actually reach - grew nothing.
 * The worker's tests passed because they called the worker. The frontend's tests passed because
 * they mock {@code fetch}. Every suite was green and the page would have answered 404 on first
 * sight, and the gap was found by curling the running stack rather than by any test in this
 * repository (2026-09-16).
 *
 * <p>The shape of that mistake is not specific to messages. Every route here is a proxy or a
 * handler written by hand next to forty others, and nothing has ever held the two lists against
 * each other.</p>
 *
 * <h2>What it does not check</h2>
 * That the answer is right, or that the worker behind a proxied route exists. This is the cheap
 * half - the path is registered at all - because that is the half that was missing and the half
 * a mocked {@code fetch} can never see.
 */
class EveryCalledPathIsRoutedTest {

    private static final String FRONTEND = "steward-ui/frontend/src";
    private static final String ROUTES =
            "steward-ui/src/main/java/eu/nordtal/s2/steward/ui/StewardUi.java";

    /**
     * An {@code /api/...} literal in the frontend, in either quote style.
     *
     * <p>It stops at the first character that ends a path: the closing quote, a {@code ?} beginning
     * a query, a {@code $} beginning an interpolation - the interpolated part is a value and not
     * a route segment, and what matters for routing is the shape up to there - or a backslash,
     * which begins an escape.</p>
     *
     * <p>The backslash was added on 2026-09-16, after this test failed on a path it had invented:
     * {@code access.tsx} prints the API's own answer in a {@code <pre>} as
     * {@code `/api/me\nwebauthn: ...`}, and without this the capture ran straight through the
     * {@code \n} and asked {@code StewardUi} to register {@code "/api/me\nwebauthn: "}. A
     * backslash cannot occur in a path, so it belongs with the other three terminators rather than
     * being worked around at the one call site that happened to hit it.</p>
     */
    private static final Pattern CALLED = Pattern.compile("[\"`](/api/[^\"`?$\\\\]*)");

    /** {@code cfg.routes.get("/api/...", ...)} and the other five verbs, plus sse. */
    private static final Pattern REGISTERED = Pattern.compile(
            "routes\\.(?:get|post|put|patch|delete|sse)\\(\"(/api/[^\"]*)\"");

    /**
     * The catch-all, which is a route and is not an answer.
     *
     * <p>{@code /api/<path>} is registered last on purpose: it turns an unmatched {@code /api}
     * request into a plain 404 instead of letting the single-page fallback claim it. It therefore
     * matches every path ever written, and counting it would make this whole test say nothing -
     * which is exactly what the first version of it did. It was written, the routes it was written
     * for were deleted to see it fail, and it passed. The bug this class exists to catch was
     * standing right there and the guard shrugged.</p>
     */
    private static final Pattern CATCH_ALL = Pattern.compile("/api/(?:\\{[^}]+}|<[^>]+>)");

    @Test
    @DisplayName("every /api path the frontend calls is registered in StewardUi")
    void nothingIsCalledThatIsNotRouted() {
        final Set<String> routes = registered();
        // A guard that silently stops guarding is worse than none: if the route table is parsed to
        // nothing, every path below "matches" nothing and the assertion would still be the one
        // that fires - but for the wrong reason, and the message would send somebody to the
        // frontend. Say it here instead.
        assertTrue(routes.size() > 20,
                "only " + routes.size() + " routes were read out of " + ROUTES
                        + " - the route table moved or the pattern stopped matching it.");

        final List<String> unrouted = new ArrayList<>();
        for (final String called : called()) {
            if (routes.stream().noneMatch(route -> matches(route, called))) {
                unrouted.add(called);
            }
        }
        assertEquals(List.of(), unrouted,
                "the browser can only reach steward-ui. A path the frontend calls and this service"
                        + " does not register is a 404 the moment somebody opens that page, and no"
                        + " suite sees it: the worker's tests call the worker, and the frontend's"
                        + " tests mock fetch. See this class's javadoc for the one that got out.");
    }

    @Test
    @DisplayName("the frontend is read, and it does call paths")
    void theFrontendIsActuallyRead() {
        final Set<String> called = called();
        assertTrue(called.size() > 15,
                "only " + called.size() + " /api paths were found in " + FRONTEND
                        + " - the tree moved, or the literals are written some other way now.");
        assertFalse(registered().contains("/api/<path>"),
                "the catch-all is a route that answers 404 and matches every path there is."
                        + " Counting it makes this test vacuous - see CATCH_ALL's javadoc.");
        assertTrue(called.contains("/api/messages"),
                "/api/messages is the path this test was written for; if it is gone, so is the"
                        + " reason to look for it, and this line should go with it.");
    }

    /**
     * Whether a Javalin route pattern covers a called path.
     *
     * <p>Both parameter styles are here because this file uses both, and they differ in exactly the
     * way that matters: {@code {name}} is one segment, {@code <file>} is the rest of the path,
     * slashes included. That is why a config file called {@code smp/config.yml} can be one
     * parameter at all.</p>
     */
    private static boolean matches(final String route, final String called) {
        // A trailing slash is where the interpolation began, not part of the path: the frontend
        // writes `/api/people/${id}/grants` and CALLED stops at the `$`, leaving "/api/people/".
        // What is known about such a call is its prefix and nothing else, so that is what is
        // asked - and it is still worth asking, because the catch-all is out of the list and a
        // prefix of no route at all is exactly the mistake this test is for.
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
        final Path root = repository().resolve(FRONTEND);
        assertTrue(Files.isDirectory(root), root + " is not there, so this test was reading"
                + " nothing. Fix the path rather than the assertion.");
        final Set<String> paths = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    // A test's fixture is not a call the browser makes, and a mocked fetch is
                    // free to name a path that never existed.
                    .filter(path -> !path.getFileName().toString().contains(".test."))
                    .filter(path -> {
                        final String name = path.getFileName().toString();
                        return name.endsWith(".ts") || name.endsWith(".tsx");
                    })
                    .forEach(file -> {
                        final Matcher matcher = CALLED.matcher(read(file));
                        while (matcher.find()) {
                            paths.add(matcher.group(1));
                        }
                    });
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return paths;
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
        assertTrue(directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
