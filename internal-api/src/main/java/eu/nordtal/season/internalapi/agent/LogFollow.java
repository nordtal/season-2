package eu.nordtal.season.internalapi.agent;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * An open log stream of the agent, read as {@link AgentWire.LogEvent}s.
 * {@link #close()} from another thread ends a {@link #read} that is blocked.
 */
public final class LogFollow implements Closeable {

    private final InputStream body;

    LogFollow(final InputStream body) {
        this.body = body;
    }

    /**
     * Passes every event to {@code events} as it arrives and returns when the stream ends.
     *
     * @throws IOException if the stream breaks or is closed during the read
     */
    public void read(final Consumer<AgentWire.LogEvent> events) throws IOException {
        final BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String event = "message";
        final StringBuilder data = new StringBuilder();
        boolean any = false;
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isEmpty()) {
                if (any) {
                    events.accept(new AgentWire.LogEvent(event, data.toString()));
                }
                event = "message";
                data.setLength(0);
                any = false;
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:")) {
                if (any) {
                    data.append('\n');
                }
                data.append(stripOneSpace(line.substring("data:".length())));
                any = true;
            }
        }
    }

    // SSE drops exactly one space after the colon and keeps any further ones.
    private static String stripOneSpace(final String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    @Override
    public void close() throws IOException {
        body.close();
    }
}
