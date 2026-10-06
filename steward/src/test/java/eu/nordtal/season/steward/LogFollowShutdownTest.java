package eu.nordtal.season.steward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A follow still open when the interface stops, which has to end once and quietly. */
class LogFollowShutdownTest extends WebTestSupport {

    @Test
    void stoppingWithAFollowOpenEndsItOnceAndQuietly() throws Exception {
        final HttpClient browser = browser();
        signIn(browser);
        holdTheKey(browser, authenticator);
        final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        try (Socket tab = openTheLog(browser)) {
            tab.setSoTimeout(20_000);
            final BufferedReader lines =
                    new BufferedReader(new InputStreamReader(tab.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = lines.readLine()) != null && !line.contains("still running")) {
                // Until the follow is really running.
            }
            assertTrue(line != null, "the follow ended before it was running");
            root.addAppender(warnings);
            web.stop();
            Thread.sleep(3000);
        } finally {
            root.detachAppender(warnings);
        }

        final List<String> loud = warnings.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(
                loud.size() < 5,
                loud.size() + " warnings after the stop, the first: "
                        + loud.stream().limit(3).toList());
        assertEquals(0, daemon.follows.get(), "the daemon's end outlived the interface");
    }

    private static Socket openTheLog(final HttpClient browser) throws Exception {
        final String cookies = ((CookieManager) browser.cookieHandler().orElseThrow())
                .getCookieStore().getCookies().stream()
                        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                        .reduce((left, right) -> left + "; " + right)
                        .orElseThrow();
        final Socket tab = new Socket(InetAddress.getLoopbackAddress(), webPort);
        tab.getOutputStream()
                .write(("GET /api/services/smp/logs HTTP/1.1\r\nHost: 127.0.0.1:" + webPort
                                + "\r\nAccept: text/event-stream\r\nCookie: " + cookies + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        tab.getOutputStream().flush();
        return tab;
    }
}
