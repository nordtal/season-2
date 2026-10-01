package eu.nordtal.s2.steward.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerSocket;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The database dump against the PostgreSQL running here, into {@code /tmp} inside the container.
 *
 * The directory is root-owned on purpose: {@code pg_dump} runs as {@code postgres}, so that is the real case.
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
        // The dump logs in as the backup role, which a stack installed before the roles existed does not have.
        final Docker.ExecResult role = docker.exec(
                postgres,
                List.of(
                        "sh",
                        "-c",
                        "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -Atc \"SELECT 1 FROM pg_roles WHERE rolname = '"
                                + DatabaseRole.BACKUP.roleName() + "'\""),
                null);
        assumeTrue(role.output().strip().equals("1"), "the postgres here has no backup role yet - skipping");
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

        // This has to be a red line in the report, never a quiet success.
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
