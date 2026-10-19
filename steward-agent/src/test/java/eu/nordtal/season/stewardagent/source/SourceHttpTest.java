package eu.nordtal.season.stewardagent.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.time.Waiting;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceHttpTest {

    private final List<Duration> pauses = new ArrayList<>();
    private final Waiting counted = new Waiting() {
        @Override
        public Instant now() {
            return Instant.EPOCH;
        }

        @Override
        public boolean sleep(final Duration duration) {
            pauses.add(duration);
            return true;
        }
    };

    @Test
    void aSourceThatCannotBeReachedIsTriedForHalfAMinuteBeforeTheRunGivesUp(@TempDir final Path root)
            throws IOException {
        final URI unreachable = unreachable();
        final var web = SourceHttp.client(Duration.ofSeconds(2), "", counted);

        assertThrows(IOException.class, () -> SourceHttp.over(web).get(unreachable));
        assertEquals(Duration.ofSeconds(31), total());

        pauses.clear();
        assertThrows(IOException.class, () -> web.download(unreachable, root.resolve("smp.jar")));
        assertEquals(Duration.ofSeconds(31), total());
    }

    private Duration total() {
        return pauses.stream().reduce(Duration.ZERO, Duration::plus);
    }

    private static URI unreachable() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/smp.jar");
        }
    }
}
