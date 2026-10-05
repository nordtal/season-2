package eu.nordtal.season.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.season.common.time.TestScheduler;
import eu.nordtal.season.database.update.ByteSize;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.stewardagent.TestProject;
import eu.nordtal.season.stewardagent.docker.Docker;
import eu.nordtal.season.stewardagent.docker.DockerSocket;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The database dump against the PostgreSQL of the project {@link TestProject#acting} names, into its {@code /tmp}.
 *
 * The directory is root-owned on purpose: {@code pg_dump} runs as {@code postgres}, so that is the real case.
 */
class DatabaseDumpIntegrationTest {

    private static final String DIRECTORY = "/tmp/steward-dump-test";

    /** The role steward's schema creates for backups, which reads everything and changes nothing. */
    private static final String ROLE = "nordtal_backup";

    private static String project;
    private static Docker docker;
    private static String postgres;

    @BeforeAll
    static void connect() {
        assumeTrue(TestProject.acting().isPresent(), TestProject.VARIABLE + " names no scratch project - skipping");
        project = TestProject.acting().orElseThrow();
        final DockerSocket socket = new DockerSocket(TestScheduler.SHARED);
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        docker = new Docker(socket);
        postgres = docker.containers(project).stream()
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
                                + ROLE + "'\""),
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
                new DatabaseDump(docker, project, DIRECTORY, Clock.systemUTC()).save("postgres", ROLE);

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

        System.out.println("dumped " + ByteSize.of(result.bytes()) + " in "
                + result.took().toMillis() + " ms to " + result.file());

        docker.exec(postgres, List.of("sh", "-c", "rm -rf " + DIRECTORY), null);
    }

    @Test
    void aDirectoryNobodyCanWriteToIsAFailureNotADumpOfNothing() {
        final SnapshotResult result =
                new DatabaseDump(docker, project, "/proc/nowhere", Clock.systemUTC()).save("postgres", ROLE);

        // This has to be a red line in the report, never a quiet success.
        assertFalse(result.ok());
        assertTrue(result.bytes() == 0, "a failed dump reported " + result.bytes() + " bytes");
        assertTrue(result.message().contains("/proc/nowhere"), result.message());
    }

    @Test
    void aServiceThatIsNotRunningIsNamedAsTheReason() {
        final SnapshotResult result =
                new DatabaseDump(docker, project, DIRECTORY, Clock.systemUTC()).save("no-such-service", ROLE);

        assertFalse(result.ok());
        assertTrue(result.message().contains("no-such-service"), result.message());
    }

    @Test
    void aRestoreReplacesTheDatabaseWithTheDump() {
        final DatabaseDump dump = new DatabaseDump(docker, project, DIRECTORY, Clock.systemUTC());
        final SnapshotResult saved = dump.save("postgres", ROLE);
        assertTrue(saved.ok(), saved.message());
        sql("CREATE TABLE s2d_probe (id int)");

        final SnapshotResult restored = dump.restore("postgres", name(saved));

        assertTrue(restored.ok(), restored.message());
        assertEquals("", sql("SELECT to_regclass('public.s2d_probe')"), "a table the dump does not hold survived");
        docker.exec(postgres, List.of("sh", "-c", "rm -rf " + DIRECTORY), null);
    }

    @Test
    void aRestoreThatFailsIsRolledBackAndNamesWhy() {
        sql("CREATE ROLE s2d_probe_owner NOLOGIN");
        sql("CREATE TABLE s2d_probe_owned (id int)");
        sql("ALTER TABLE s2d_probe_owned OWNER TO s2d_probe_owner");
        final DatabaseDump dump = new DatabaseDump(docker, project, DIRECTORY, Clock.systemUTC());
        final SnapshotResult saved = dump.save("postgres", ROLE);
        sql("DROP TABLE s2d_probe_owned");
        sql("DROP ROLE s2d_probe_owner");
        assertTrue(saved.ok(), saved.message());
        sql("CREATE TABLE s2d_probe_kept (id int)");

        final SnapshotResult restored = dump.restore("postgres", name(saved));

        final String kept = sql("SELECT to_regclass('public.s2d_probe_kept')");
        sql("DROP TABLE IF EXISTS s2d_probe_kept");
        assertEquals("s2d_probe_kept", kept, "a failed restore left the database changed");
        docker.exec(postgres, List.of("sh", "-c", "rm -rf " + DIRECTORY), null);
        assertFalse(restored.ok());
        assertTrue(restored.message().contains("s2d_probe_owner"), restored.message());
    }

    private static String name(final SnapshotResult saved) {
        return saved.file().substring(DIRECTORY.length() + 1);
    }

    private static String sql(final String statement) {
        final Docker.ExecResult result = docker.exec(
                postgres,
                List.of("sh", "-c", "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -Atc \"" + statement + "\""),
                null);
        assertTrue(result.ok(), statement + ": " + result.output());
        return result.output().strip();
    }
}
