package eu.nordtal.s2.steward.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Docker daemon on a socket of its own, with one container: {@code smp} of {@code nordtal-s2}, running.
 *
 * It answers only what the routes under test ask; anything else is a 404, which every caller already handles.
 */
final class FakeDaemon implements AutoCloseable {

    static final String PROJECT = "nordtal-s2";
    private static final String CONTAINER = "c0ffee";

    private static final String LINE = "2026-10-01T10:00:00.000000000Z [12:00:00 INFO]: still running\n";

    /** Whether the daemon answers the container list with a 500, as one mid-restart does. */
    final AtomicBoolean broken = new AtomicBoolean();

    /** Whether a followed log keeps producing lines; a quiet one writes nothing at all, as a quiet server does. */
    final AtomicBoolean chatty = new AtomicBoolean(true);

    /** How many log follows are open at this end, counted until the reader hangs up. */
    final AtomicInteger follows = new AtomicInteger();

    /** Every exec request body, so a test can see what reached the console. */
    final List<String> execs = new CopyOnWriteArrayList<>();

    private final Path socket;
    private final ServerSocketChannel server;
    private final Thread acceptor;

    FakeDaemon(final Path directory) throws IOException {
        this.socket = directory.resolve("docker.sock");
        Files.deleteIfExists(socket);
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        this.acceptor = Thread.ofPlatform().daemon().name("fake-daemon").start(this::accept);
    }

    /** Where the socket is, for {@code new DockerSocket(path, timeout)}. */
    Path socket() {
        return socket;
    }

    private void accept() {
        while (server.isOpen()) {
            try {
                final SocketChannel client = server.accept();
                Thread.ofVirtual().name("fake-daemon-request").start(() -> answer(client));
            } catch (final IOException closed) {
                return;
            }
        }
    }

    private void answer(final SocketChannel client) {
        try (client) {
            final String head = readHead(client);
            final String[] requestLine = head.substring(0, head.indexOf('\r')).split(" ", -1);
            final String method = requestLine[0];
            final String path = requestLine[1];
            final int length = contentLength(head);
            final String body = length == 0 ? "" : readBody(client, length);
            route(client, method, path, body);
        } catch (final IOException gone) {
            // The other end hung up, which is how every follow ends.
        }
    }

    private void route(final SocketChannel client, final String method, final String path, final String body)
            throws IOException {
        if (path.startsWith("/containers/json")) {
            if (broken.get()) {
                json(client, 500, "{\"message\":\"the daemon is not answering\"}");
                return;
            }
            json(client, 200, """
                    [{"Id":"%s","Names":["/nordtal-s2-smp-1"],"Image":"ghcr.io/nordtal/minecraft:latest",
                      "ImageID":"sha256:5eed","State":"running","Status":"Up 2 hours",
                      "Labels":{"com.docker.compose.project":"%s","com.docker.compose.service":"smp"}}]
                    """.formatted(CONTAINER, PROJECT));
        } else if (path.equals("/containers/" + CONTAINER + "/json")) {
            json(client, 200, """
                    {"Id":"%s","Name":"/nordtal-s2-smp-1","Image":"sha256:5eed",
                     "Config":{"Image":"ghcr.io/nordtal/minecraft:latest","Tty":true},
                     "State":{"Status":"running","StartedAt":"2026-10-01T08:00:00Z","ExitCode":0}}
                    """.formatted(CONTAINER));
        } else if (path.startsWith("/containers/" + CONTAINER + "/stats")) {
            json(client, 200, "{}");
        } else if (path.startsWith("/containers/" + CONTAINER + "/logs") && path.contains("follow=1")) {
            follow(client);
        } else if (path.startsWith("/containers/" + CONTAINER + "/logs")) {
            final byte[] lines = LINE.getBytes(StandardCharsets.UTF_8);
            write(
                    client,
                    "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + lines.length + "\r\n\r\n");
            write(client, LINE);
        } else if (method.equals("POST") && path.equals("/containers/" + CONTAINER + "/exec")) {
            execs.add(body);
            json(client, 201, "{\"Id\":\"e1\"}");
        } else if (method.equals("POST") && path.equals("/exec/e1/start")) {
            write(client, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
        } else if (path.equals("/exec/e1/json")) {
            json(client, 200, "{\"ExitCode\":0,\"Running\":false}");
        } else {
            json(client, 404, "{\"message\":\"not in this stand-in: " + method + " " + path + "\"}");
        }
    }

    /** A follow: a line now, then one every 120 ms while chatty, until the reader closes its end. */
    private void follow(final SocketChannel client) throws IOException {
        follows.incrementAndGet();
        final AtomicBoolean hungUp = new AtomicBoolean();
        // Only a read sees a hangup while nothing is written, which is what a quiet log is.
        Thread.ofVirtual().name("fake-daemon-hangup").start(() -> {
            try {
                final ByteBuffer one = ByteBuffer.allocate(1);
                while (client.read(one) != -1) {
                    one.clear();
                }
            } catch (final IOException closed) {
                // Same thing.
            }
            hungUp.set(true);
        });
        try {
            write(client, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n");
            write(client, LINE);
            while (!hungUp.get()) {
                Thread.sleep(120);
                if (chatty.get()) {
                    write(client, LINE);
                }
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            follows.decrementAndGet();
        }
    }

    private static void json(final SocketChannel client, final int status, final String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        write(
                client,
                "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                        + "\r\n\r\n");
        client.write(ByteBuffer.wrap(bytes));
    }

    private static void write(final SocketChannel client, final String text) throws IOException {
        final ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            client.write(buffer);
        }
    }

    /** The request line and headers, read a byte at a time so the body stays in the channel. */
    private static String readHead(final SocketChannel client) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        final ByteBuffer one = ByteBuffer.allocate(1);
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            one.clear();
            if (client.read(one) == -1) {
                throw new IOException("the request ended inside its headers");
            }
            head.write(one.get(0));
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private static int contentLength(final String head) {
        for (final String line : head.split("\r\n", -1)) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return 0;
    }

    private static String readBody(final SocketChannel client, final int length) throws IOException {
        final ByteBuffer body = ByteBuffer.allocate(length);
        while (body.hasRemaining()) {
            if (client.read(body) == -1) {
                break;
            }
        }
        return new String(body.array(), 0, body.position(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
        Files.deleteIfExists(socket);
    }
}
