package eu.nordtal.s2.steward.docker;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP/1.1 over the Docker socket, by hand: the JDK client cannot dial a unix socket, Docker clients bring Jackson.
 *
 * One connection per request. A watchdog closes the channel on timeout, until the headers of a follow are in.
 */
public final class DockerSocket {

    private static final Logger log = LoggerFactory.getLogger(DockerSocket.class);

    /** Where the socket is in every one of our containers, and on this host. */
    public static final Path DEFAULT_SOCKET = Path.of("/var/run/docker.sock");

    /** No API version in the path, so the daemon answers with the newest shape it supports. */
    private static final String HOST_HEADER = "docker";

    private final Path socket;
    private final Duration timeout;
    private final ScheduledExecutorService watchdog;

    public DockerSocket() {
        this(DEFAULT_SOCKET, Duration.ofSeconds(30));
    }

    public DockerSocket(final Path socket, final Duration timeout) {
        this.socket = socket;
        this.timeout = timeout;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "docker-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Whether the socket is there at all, asked once at startup. */
    public boolean isReachable() {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return channel.isConnected();
        } catch (IOException e) {
            return false;
        }
    }

    /** A request whose whole body is read at once; the connection is closed before this returns. */
    public String send(final String method, final String path, final @Nullable String jsonBody) {
        try (Stream stream = open(method, path, jsonBody, timeout)) {
            final String body = new String(stream.body().readAllBytes(), StandardCharsets.UTF_8);
            if (stream.status() >= 400) {
                throw new DockerException(
                        method + " " + path + " answered " + stream.status(), stream.status(), body, null);
            }
            return body;
        } catch (IOException e) {
            throw new DockerException("reading " + method + " " + path, e);
        }
    }

    /** A request whose body is read as it arrives with no watchdog; closing the returned stream ends a log follow. */
    public Stream stream(final String method, final String path, final @Nullable String jsonBody) {
        return stream(method, path, jsonBody, null);
    }

    /** A stream with a deadline, for an exec that must finish rather than sit silent. */
    public Stream stream(
            final String method,
            final String path,
            final @Nullable String jsonBody,
            final @Nullable Duration deadline) {
        final Stream stream = open(method, path, jsonBody, deadline);
        if (stream.status() >= 400) {
            String body = "";
            try (Stream toClose = stream) {
                body = new String(toClose.body().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // The status is the diagnosis; an unreadable body does not change it.
            }
            throw new DockerException(
                    method + " " + path + " answered " + stream.status(), stream.status(), body, null);
        }
        return stream;
    }

    private Stream open(
            final String method,
            final String path,
            final @Nullable String jsonBody,
            final @Nullable Duration deadline) {
        SocketChannel channel = null;
        ScheduledFuture<?> alarm = null;
        try {
            // The watchdog is set before the connect, which itself blocks on a wedged daemon.
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            // A call with a deadline is watched to its end; a log follow only until the headers are in.
            final Duration untilItAnswers = deadline == null ? timeout : deadline;
            final SocketChannel toClose = channel;
            alarm = watchdog.schedule(
                    () -> closeQuietly(toClose, method + " " + path), untilItAnswers.toMillis(), TimeUnit.MILLISECONDS);
            channel.connect(UnixDomainSocketAddress.of(socket));
            write(channel, method, path, jsonBody);

            final BufferedInputStream raw = new BufferedInputStream(Channels.newInputStream(channel), 16 * 1024);
            final StatusLine statusLine = readStatusLine(raw, method, path);
            final Map<String, String> headers = readHeaders(raw);
            final InputStream body = bodyOf(raw, headers);
            if (deadline == null) {
                // The daemon answered, so the follow may now be silent.
                alarm.cancel(false);
                alarm = null;
            }
            return new Stream(statusLine.status(), headers, body, channel, alarm);
        } catch (IOException e) {
            closeQuietly(channel, method + " " + path);
            if (alarm != null) {
                alarm.cancel(false);
            }
            throw new DockerException("talking to the docker socket at " + socket + " for " + method + " " + path, e);
        } catch (RuntimeException e) {
            closeQuietly(channel, method + " " + path);
            if (alarm != null) {
                alarm.cancel(false);
            }
            throw e;
        }
    }

    private void write(
            final SocketChannel channel, final String method, final String path, final @Nullable String jsonBody)
            throws IOException {
        final StringBuilder request = new StringBuilder()
                .append(method)
                .append(' ')
                .append(path)
                .append(" HTTP/1.1\r\n")
                .append("Host: ")
                .append(HOST_HEADER)
                .append("\r\n")
                .append("Accept: application/json\r\n")
                .append("Connection: close\r\n");
        final byte[] payload = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        if (jsonBody != null) {
            request.append("Content-Type: application/json\r\n")
                    .append("Content-Length: ")
                    .append(payload.length)
                    .append("\r\n");
        }
        request.append("\r\n");

        final OutputStream out = Channels.newOutputStream(channel);
        out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    private StatusLine readStatusLine(final InputStream in, final String method, final String path) throws IOException {
        final String line = readLine(in);
        if (line == null || !line.startsWith("HTTP/")) {
            throw new DockerException(
                    "the socket answered something that is not HTTP for " + method + " " + path + ": " + line);
        }
        final String[] parts = line.split(" ", 3);
        if (parts.length < 2) {
            throw new DockerException("unreadable status line: " + line);
        }
        return new StatusLine(Integer.parseInt(parts[1]));
    }

    private Map<String, String> readHeaders(final InputStream in) throws IOException {
        final Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            final int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** Reads one CRLF-terminated line a byte at a time, so the body after the headers is not read ahead. */
    private static @Nullable String readLine(final InputStream in) throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                final byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            line.write(current);
            previous = current;
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
    }

    private static InputStream bodyOf(final InputStream in, final Map<String, String> headers) {
        final String encoding = headers.getOrDefault("transfer-encoding", "");
        if (encoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return new ChunkedInputStream(in);
        }
        final String length = headers.get("content-length");
        if (length != null) {
            try {
                return new LimitedInputStream(in, Long.parseLong(length.trim()));
            } catch (NumberFormatException e) {
                throw new DockerException("unreadable Content-Length: " + length, e);
            }
        }
        // Neither header: the daemon closes the connection to mark the end.
        return in;
    }

    private static void closeQuietly(final @Nullable SocketChannel channel, final String what) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("closing the docker socket after {}", what, e);
        }
    }

    private record StatusLine(int status) {}

    /** One open response: the status, the headers, and a body that is read until it is closed. */
    public static final class Stream implements AutoCloseable {

        private final int status;
        private final Map<String, String> headers;
        private final InputStream body;
        private final SocketChannel channel;
        private final @Nullable ScheduledFuture<?> alarm;

        Stream(
                final int status,
                final Map<String, String> headers,
                final InputStream body,
                final SocketChannel channel,
                final @Nullable ScheduledFuture<?> alarm) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.channel = channel;
            this.alarm = alarm;
        }

        public int status() {
            return status;
        }

        public InputStream body() {
            return body;
        }

        /** {@code application/vnd.docker.multiplexed-stream} when the frames carry a header. */
        public String contentType() {
            return headers.getOrDefault("content-type", "");
        }

        @Override
        public void close() throws IOException {
            if (alarm != null) {
                alarm.cancel(false);
            }
            channel.close();
        }
    }

    /** {@code Transfer-Encoding: chunked}, decoded. */
    private static final class ChunkedInputStream extends InputStream {

        private final InputStream in;
        private long remaining;
        private boolean finished;

        ChunkedInputStream(final InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (finished) {
                return -1;
            }
            if (remaining == 0 && !nextChunk()) {
                return -1;
            }
            final int wanted = (int) Math.min(length, remaining);
            final int read = in.read(buffer, offset, wanted);
            if (read == -1) {
                throw new IOException("the connection ended inside a chunk");
            }
            remaining -= read;
            if (remaining == 0) {
                // The CRLF closing the chunk is not data.
                readLine(in);
            }
            return read;
        }

        private boolean nextChunk() throws IOException {
            final String header = readLine(in);
            if (header == null) {
                finished = true;
                return false;
            }
            final String size = header.contains(";") ? header.substring(0, header.indexOf(';')) : header;
            final long length = Long.parseLong(size.trim(), 16);
            if (length == 0) {
                finished = true;
                readLine(in); // the trailer's empty line
                return false;
            }
            remaining = length;
            return true;
        }
    }

    /** {@code Content-Length} bytes and not one more, so EOF lands where the body ends. */
    private static final class LimitedInputStream extends InputStream {

        private final InputStream in;
        private long remaining;

        LimitedInputStream(final InputStream in, final long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            final int value = in.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            final int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
