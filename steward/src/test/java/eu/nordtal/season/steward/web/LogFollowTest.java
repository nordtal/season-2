package eu.nordtal.season.steward.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** Following a service's log over SSE, and what closes the connection. */
class LogFollowTest extends WebTestSupport {

    @Test
    void aClosedTabClosesTheFollowAtTheDaemon() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        // Over a socket, closed and then waited out, so a leftover follow does not flood later classes.
        final int whileFollowing;
        try (java.net.Socket tab = openTheLogOverASocket(browser)) {
            final BufferedReader lines =
                    new BufferedReader(new InputStreamReader(tab.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(waitForALineSaying(lines, "event: line"), "the line event lost its name");
            assertTrue(waitForALineSaying(lines, "still running"));
            whileFollowing = daemon.follows.get();
        }
        assertTrue(theFollowsConnectionClosed(whileFollowing), "the follow outlived its socket");
    }

    @Test
    void loggingOutEndsTheFollow() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        // A Discord session alone reads nothing; the follow is what is under test.
        holdTheKey(browser, authenticator);
        final HttpResponse<InputStream> follow = openTheLog(browser);
        final int whileFollowing;
        try (BufferedReader lines = reader(follow)) {
            assertTrue(waitForALineSaying(lines, "still running"), "nothing was ever followed");
            whileFollowing = daemon.follows.get();

            assertEquals(204, logout(browser).statusCode());

            // The check at the top of the route runs once, at connect; a follow outlives it by hours.
            assertTrue(
                    waitForALineSaying(lines, "this session ended"),
                    "the logs kept arriving after the session had been thrown away");
        }
        assertTrue(theFollowsConnectionClosed(whileFollowing), "the daemon's end was left open");
    }

    @Test
    void aQuietFollowSurvivesAndThenIsCleanedUp() throws Exception {
        // The slowest test in this module: a quiet log is a healthy one.
        daemon.chatty.set(false);
        try {
            final HttpClient browser = browser();
            signIn(browser);
            holdTheKey(browser, authenticator);
            // A real socket, not an HttpClient: closing a JDK response body leaves the connection open.
            final java.net.Socket tab = openTheLogOverASocket(browser);
            final BufferedReader lines =
                    new BufferedReader(new InputStreamReader(tab.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(waitForALineSaying(lines, "still running"), "nothing was ever followed");
            final int whileFollowing = daemon.follows.get();
            assertTrue(whileFollowing >= 1, "the follow is not open at the daemon at all");

            // Jetty drops a connection nothing has written on for thirty seconds; three beats is past that.
            for (int beat = 1; beat <= 3; beat++) {
                assertTrue(
                        waitForALineSaying(lines, "following smp"),
                        "the follow went quiet and died after beat " + (beat - 1));
            }

            // The tab closes, so the socket goes; the JDK's client does not close it on its own.
            tab.close();

            // Each reload would otherwise leave a connection open here, and a log stream at the daemon.
            assertTrue(
                    theFollowsConnectionClosed(whileFollowing),
                    "the daemon's end outlived the browser's: " + daemon.follows.get() + " follows open, "
                            + whileFollowing + " during the follow");
        } finally {
            daemon.chatty.set(true);
        }
    }

    /** The same follow over a socket this test can really close, signed in with the browser's own cookie. */
    private static java.net.Socket openTheLogOverASocket(final HttpClient browser) throws Exception {
        final java.net.CookieHandler jar = browser.cookieHandler().orElseThrow();
        final String cookies = ((CookieManager) jar)
                .getCookieStore().getCookies().stream()
                        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                        .reduce((left, right) -> left + "; " + right)
                        .orElseThrow(() -> new AssertionError("this browser has no session cookie"));
        final java.net.Socket tab = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), WEB_PORT);
        tab.getOutputStream()
                .write(("GET /api/services/smp/logs HTTP/1.1\r\n"
                                + "Host: 127.0.0.1:" + WEB_PORT + "\r\n"
                                + "Accept: text/event-stream\r\n"
                                + "Cookie: " + cookies + "\r\n"
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        tab.getOutputStream().flush();
        return tab;
    }

    private static HttpResponse<InputStream> openTheLog(final HttpClient browser) throws Exception {
        final HttpResponse<InputStream> follow = browser.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + WEB_PORT + "/api/services/smp/logs"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, follow.statusCode());
        return follow;
    }

    private static BufferedReader reader(final HttpResponse<InputStream> follow) {
        return new BufferedReader(new InputStreamReader(follow.body(), StandardCharsets.UTF_8));
    }

    /** Reads the stream until it says that, it ends, or twenty seconds pass, on its own thread so a hang fails. */
    private static boolean waitForALineSaying(final BufferedReader lines, final String text) throws Exception {
        // A daemon thread: an ordinary one would still be parked in readLine() when the suite ends.
        final ExecutorService one = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "follow-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return one.submit(() -> {
                        String line;
                        while ((line = lines.readLine()) != null) {
                            if (line.contains(text)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .get(20, TimeUnit.SECONDS);
        } catch (final TimeoutException never) {
            return false;
        } finally {
            one.shutdownNow();
        }
    }

    /** Waits up to 45 seconds for the daemon's end to close, since only the second write after a close fails. */
    private static boolean theFollowsConnectionClosed(final int whileFollowing) throws InterruptedException {
        // Strictly fewer than during the follow, since another class's follow may still be winding down.
        for (int attempt = 0; attempt < 450 && daemon.follows.get() >= whileFollowing; attempt++) {
            Thread.sleep(100);
        }
        return daemon.follows.get() < whileFollowing;
    }
}
