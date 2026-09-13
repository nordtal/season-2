package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The database dump against the PostgreSQL that is actually running here.
 *
 * <p>It dumps the live season database - which is a read, taken as an MVCC snapshot, and changes
 * nothing - into {@code /tmp} inside the postgres container rather than into the shared backup
 * directory, because that mount does not exist until the cutover. Everything else is the real
 * path: the real {@code pg_dump}, the real verification, the real rename, the real file size.</p>
 */
class DatabaseDumpIntegrationTest {

    private static final String PROJECT = "nordtal-s2";
    private static final String DIRECTORY = "/tmp/steward-dump-test";

    private static Docker docker;
    private static String postgres;

    @BeforeAll
    static void connect() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        docker = new Docker(socket);
        postgres = docker.containers(PROJECT).stream()
                .filter(container -> "postgres".equals(container.service()) && container.isRunning())
                .map(Docker.Container::id)
                .findFirst()
                .orElse(null);
        assumeTrue(postgres != null, "no postgres container here - skipping");
        docker.exec(postgres, List.of("sh", "-c", "mkdir -p " + DIRECTORY
                + " && chown postgres " + DIRECTORY), null);
    }

    @Test
    @DisplayName("the live database dumps, verifies, and lands under its final name")
    void dumpsTheLiveDatabase() {
        final SnapshotResult result = new DatabaseDump(docker, PROJECT, "postgres", DIRECTORY,
                Clock.systemUTC()).save();

        assertTrue(result.ok(), result.message());
        assertNotNull(result.file());
        assertTrue(result.bytes() > 1024,
                "a dump of this database should not be " + result.bytes() + " bytes");
        assertFalse(result.file().endsWith(".partial"),
                "a dump still called .partial has not been verified");

        // The file is where it says it is, and nothing partial was left behind.
        final Docker.ExecResult listing = docker.exec(postgres,
                List.of("sh", "-c", "ls " + DIRECTORY), null);
        assertTrue(listing.output().contains(result.file().substring(DIRECTORY.length() + 1)),
                listing.output());
        assertFalse(listing.output().contains(".partial"),
                "a partial file survived a successful dump: " + listing.output());

        System.out.println("dumped " + SnapshotResult.human(result.bytes())
                + " in " + result.took().toMillis() + " ms to " + result.file());

        docker.exec(postgres, List.of("sh", "-c", "rm -rf " + DIRECTORY), null);
    }

    @Test
    @DisplayName("a directory nobody can write to is a failure, not a dump of nothing")
    void refusesWhatItCannotWrite() {
        final SnapshotResult result = new DatabaseDump(docker, PROJECT, "postgres",
                "/proc/nowhere", Clock.systemUTC()).save();

        // The A23 shape: this has to be a red line in the report, never a quiet success.
        assertFalse(result.ok());
        assertTrue(result.bytes() == 0, "a failed dump reported " + result.bytes() + " bytes");
        assertTrue(result.message().toLowerCase().contains("pg_dump"), result.message());
    }

    @Test
    @DisplayName("a service that is not running is named as the reason")
    void saysWhenThereIsNoDatabase() {
        final SnapshotResult result = new DatabaseDump(docker, PROJECT, "no-such-service",
                DIRECTORY, Clock.systemUTC()).save();

        assertFalse(result.ok());
        assertTrue(result.message().contains("no-such-service"), result.message());
    }
}
