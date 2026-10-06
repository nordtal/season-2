package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.notify.SignalHub;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The one live stream: a write somebody signals reaches a reader as a change of its topic. */
class LiveStreamTest extends WebTestSupport {

    private static SignalHub hub;

    @BeforeAll
    static void listen() {
        // The production hub over the steward role, so a channel steward cannot LISTEN on fails here.
        hub = SignalHub.open(
                postgres.jdbcUrl(),
                DatabaseRole.STEWARD.roleName(),
                DatabaseRole.STEWARD.key(),
                10,
                "live-stream-test",
                LoggerFactory.getLogger(LiveStreamTest.class));
        web.listen(hub);
        hub.start();
    }

    @AfterAll
    static void close() {
        hub.close();
    }

    @Test
    void aRunSomebodyAskedForIsAnnouncedAsAChangeOfTheRuns() throws Exception {
        settleOpenRuns();
        try (Socket tab = openTheStream()) {
            final BufferedReader lines =
                    new BufferedReader(new InputStreamReader(tab.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(readUntil(lines, "text/event-stream"), "the stream did not open");
            // The first pass after a reader arrives records every version without announcing any.
            Thread.sleep(2_500);

            assertEquals(
                    202,
                    post("/api/updates", "{\"kind\":\"RECREATE\",\"services\":[\"smp\"]}")
                            .statusCode());

            assertTrue(readUntil(lines, "\"topic\":\"RUNS\""), "the run never reached the stream");
        } finally {
            settleOpenRuns();
        }
    }

    /** {@code /api/live} over a socket this test can really close, with the signed-in cookie. */
    private static Socket openTheStream() throws Exception {
        final String cookies = ((CookieManager) http.cookieHandler().orElseThrow())
                .getCookieStore().getCookies().stream()
                        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                        .reduce((left, right) -> left + "; " + right)
                        .orElseThrow(() -> new AssertionError("this browser has no session cookie"));
        final Socket tab = new Socket(InetAddress.getLoopbackAddress(), webPort);
        tab.getOutputStream()
                .write(("GET /api/live HTTP/1.1\r\n"
                                + "Host: 127.0.0.1:" + webPort + "\r\n"
                                + "Accept: text/event-stream\r\n"
                                + "Cookie: " + cookies + "\r\n"
                                + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        tab.getOutputStream().flush();
        return tab;
    }

    /** Reads until a line says that, the stream ends, or twenty seconds pass, on its own thread so a hang fails. */
    private static boolean readUntil(final BufferedReader lines, final String text) throws Exception {
        final ExecutorService one = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "live-test-reader");
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
}
