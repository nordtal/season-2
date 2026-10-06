package eu.nordtal.season.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** That no timer of the proxy reads database state, which is the signal hub's job and nobody else's. */
class ProxyTimersTest {

    /** What repeats on a clock: writes, the head count of a standby, a sweep of memory and the readiness beat. */
    private static final Set<String> CLOCK_DRIVEN = Set.of(
            "onlineWriter::tick", "writer::flushAll", "packs::sweep", "standbyReturn::check", "readiness::refresh");

    private static final Pattern EVERY = Pattern.compile("scheduler\\s*\\.every\\([^;]*?(\\w+::\\w+)\\)\\s*;");

    @Test
    void everyRepeatingTimerIsOneThatReadsNoDatabaseState() throws IOException {
        final String wiring = Files.readString(
                RepositoryRoot.resolve("proxy/src/main/java/eu/nordtal/season/proxy/ProxyPlugin.java"));
        final Set<String> timers = new TreeSet<>();
        final Matcher matcher = EVERY.matcher(wiring);
        while (matcher.find()) {
            timers.add(matcher.group(1));
        }

        assertFalse(timers.isEmpty(), "found no timer at all, so the search itself is broken");
        assertEquals(
                new TreeSet<>(CLOCK_DRIVEN),
                timers,
                "a repeating timer was added or removed; one that reads database state belongs on the signal hub"
                        + " (hub.on), and a timer that only writes is added to CLOCK_DRIVEN");
    }
}
