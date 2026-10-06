package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code up} hands a confined service's volume to its uid before the container starts, against a real daemon.
 *
 * A scratch project of its own in a temporary directory, on a small image; it skips itself without the docker CLI
 * or that image.
 */
class HandOverIntegrationTest {

    private static final String IMAGE = "alpine:3.20";

    @TempDir
    Path directory;

    private Compose compose;

    @Test
    void aServiceThatRunsAsItsOwnUidCanWriteAVolumeThatWasNotItsOwn() throws Exception {
        assumeTrue(imageIsHere(), "no docker CLI, or no " + IMAGE + " to be had - skipping");
        final Path data = Files.createDirectories(directory.resolve("data"));
        final Path old = Files.writeString(data.resolve("old"), "written before the service had a user");
        final Path untouched = Files.writeString(
                Files.createDirectories(directory.resolve("other")).resolve("old"), "the other service's");
        final long before = uid(old);
        final long group = gid(old);
        Files.writeString(directory.resolve("compose.yml"), """
                services:
                  box:
                    image: %1$s
                    user: "4321:4321"
                    cap_drop: [ALL]
                    security_opt: ["no-new-privileges:true"]
                    restart: "no"
                    network_mode: none
                    command: ["touch", "/data/written"]
                    volumes: ["./data:/data"]
                  other:
                    image: %1$s
                    restart: "no"
                    network_mode: none
                    command: ["true"]
                    volumes: ["./other:/data"]
                """.formatted(IMAGE), StandardCharsets.UTF_8);
        compose = new Compose(
                directory.resolve("compose.yml"),
                directory.resolve("absent.env"),
                directory,
                "nordtal-handover-" + ProcessHandle.current().pid());
        final List<String> output = new ArrayList<>();

        assertEquals(0, compose.up(List.of("box", "other"), output::add), String.join("\n", output));

        assertTrue(appears(data.resolve("written")), "the service could not write its volume: " + output);
        assertEquals(4321L, uid(data), output.toString());
        assertEquals(4321L, uid(old), output.toString());
        assertEquals(before, uid(untouched), "a service without a user had its volume handed to someone");

        // Handed back the same way, so the temporary directory can go wherever the test runs.
        final List<String> back = compose.handOverCommand(
                "box", new Compose.Owner(Long.toString(before), Long.toString(group), List.of("/data")));
        assertEquals(0, exec(back.toArray(String[]::new)), back.toString());
        assertEquals(before, uid(old));
    }

    @AfterEach
    void down() throws IOException {
        if (compose != null) {
            exec(compose.command(List.of("down", "--remove-orphans")).toArray(String[]::new));
        }
    }

    private static boolean imageIsHere() {
        try {
            if (exec("docker", "image", "inspect", IMAGE) == 0) {
                return true;
            }
            return exec("docker", "pull", "--quiet", IMAGE) == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static int exec(final String... command) throws IOException {
        try {
            return new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private static boolean appears(final Path file) throws InterruptedException {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(file)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static long uid(final Path path) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:uid")).longValue();
    }

    private static long gid(final Path path) throws IOException {
        return ((Number) Files.getAttribute(path, "unix:gid")).longValue();
    }
}
