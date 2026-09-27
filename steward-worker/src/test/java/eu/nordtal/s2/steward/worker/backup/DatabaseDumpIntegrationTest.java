package eu.nordtal.s2.steward.worker.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The database dump against the PostgreSQL that is actually running here.
 *
 * It dumps the live season database - which is a read, taken as an MVCC snapshot, and changes nothing - into
 * {@code /tmp} inside the postgres container rather than into the shared backup directory, because a test has no
 * business writing into the real one. Everything else is the real path: the real {@code pg_dump}, the real
 * verification, the real rename, the real file size.
 *
 * The directory is created root-owned on purpose. {@code pg_dump} runs as {@code postgres}, so a root-owned
 * directory is the whole of finding 39 - and the setup here used to hand the directory over itself, which is why the
 * test stayed green for months while not one dump was ever written on the running stack.
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
        // Root-owned and 0755, what a fresh docker volume looks like; handing it to `postgres` would hide the bug here.
        docker.exec(
                postgres,
                List.of(
                        "sh",
                        "-c",
                        "rm -rf " + DIRECTORY + " && mkdir -p " + DIRECTORY + " && chown root " + DIRECTORY
                                + " && chmod 755 " + DIRECTORY),
                null);
    }

    @Test
    void theLiveDatabaseDumpsVerifiesAndLandsUnderItsFinalName() {
        final SnapshotResult result =
                new DatabaseDump(docker, PROJECT, "postgres", DIRECTORY, Clock.systemUTC()).save();

        assertTrue(result.ok(), result.message());
        assertNotNull(result.file());
        assertTrue(result.bytes() > 1024, "a dump of this database should not be " + result.bytes() + " bytes");
        assertFalse(result.file().endsWith(".partial"), "a dump still called .partial has not been verified");

        // The file is where it says it is, and nothing partial was left behind.
        final Docker.ExecResult listing = docker.exec(postgres, List.of("sh", "-c", "ls " + DIRECTORY), null);
        assertTrue(listing.output().contains(result.file().substring(DIRECTORY.length() + 1)), listing.output());
        assertFalse(
                listing.output().contains(".partial"),
                "a partial file survived a successful dump: " + listing.output());

        System.out.println("dumped " + SnapshotResult.human(result.bytes()) + " in "
                + result.took().toMillis() + " ms to " + result.file());

        docker.exec(postgres, List.of("sh", "-c", "rm -rf " + DIRECTORY), null);
    }

    @Test
    void aDirectoryNobodyCanWriteToIsAFailureNotADumpOfNothing() {
        final SnapshotResult result =
                new DatabaseDump(docker, PROJECT, "postgres", "/proc/nowhere", Clock.systemUTC()).save();

        // The A23 shape: this has to be a red line in the report, never a quiet success.
        assertFalse(result.ok());
        assertTrue(result.bytes() == 0, "a failed dump reported " + result.bytes() + " bytes");
        assertTrue(result.message().contains("/proc/nowhere"), result.message());
    }

    @Test
    void aServiceThatIsNotRunningIsNamedAsTheReason() {
        final SnapshotResult result =
                new DatabaseDump(docker, PROJECT, "no-such-service", DIRECTORY, Clock.systemUTC()).save();

        assertFalse(result.ok());
        assertTrue(result.message().contains("no-such-service"), result.message());
    }
}
