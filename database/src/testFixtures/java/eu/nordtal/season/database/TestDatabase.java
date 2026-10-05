package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A database with the schema applied, for any test that needs PostgreSQL.
 *
 * One container serves the whole test JVM and migrates once; every {@link #fresh()} is a new database cloned from it.
 */
public final class TestDatabase {

    /** The image, held against {@code compose.yml}'s default by {@code TestDatabaseImageTest}. */
    public static final String IMAGE = "postgres:17-alpine";

    /** The migrated database every fresh one is cloned from; nothing connects to it after the migration. */
    private static final String TEMPLATE = "schema";

    private static final AtomicInteger CLONES = new AtomicInteger();

    private final PGSimpleDataSource dataSource;
    private final PostgreSQLContainer<?> running;
    private final String name;

    private TestDatabase(final PGSimpleDataSource dataSource, final PostgreSQLContainer<?> running, final String name) {
        this.dataSource = dataSource;
        this.running = running;
        this.name = name;
    }

    /**
     * Returns a new database holding the schema and nothing written by any other test.
     *
     * Aborts the calling test, which JUnit reports as skipped, when no Docker daemon is reachable.
     */
    public static TestDatabase fresh() {
        return create("TEMPLATE " + TEMPLATE);
    }

    /** Returns a new database without the schema, for a test of migrating or of refusing an unmigrated one. */
    public static TestDatabase empty() {
        return create("TEMPLATE template0");
    }

    /**
     * Returns a new database restored from a {@code pg_dump -Fc} file and migrated to the newest schema.
     *
     * The dump's owner role is created first, so ownership and grants come back as the dump has them.
     */
    public static TestDatabase restored(final java.nio.file.Path dump, final String owner) {
        final TestDatabase restored = empty();
        execute(
                restored.running,
                "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + owner + "') THEN CREATE ROLE "
                        + owner + "; END IF; END $$");
        final String inside = "/tmp/" + restored.name + ".dump";
        restored.running.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(dump), inside);
        try {
            final org.testcontainers.containers.Container.ExecResult result = restored.running.execInContainer(
                    "pg_restore", "--exit-on-error", "-U", restored.username(), "-d", restored.name, inside);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("pg_restore refused " + dump + ": " + result.getStderr());
            }
        } catch (final java.io.IOException | InterruptedException failure) {
            throw new IllegalStateException("pg_restore did not run in the test container", failure);
        }
        restored.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");
        return restored;
    }

    private static TestDatabase create(final String template) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon reachable");
        final PostgreSQLContainer<?> running = Container.RUNNING;
        final String name = "test_" + CLONES.incrementAndGet();
        execute(running, "CREATE DATABASE " + name + " " + template);
        return new TestDatabase(dataSource(running, name), running, name);
    }

    /** Returns a data source that opens a new connection to this database on every call. */
    public DataSource dataSource() {
        return dataSource;
    }

    /** Returns the JDBC URL, for code that builds its own pool. */
    public String jdbcUrl() {
        return dataSource.getUrl();
    }

    /** Returns the user, a superuser of the test container. */
    public String username() {
        return running.getUsername();
    }

    /** Returns the user's password. */
    public String password() {
        return running.getPassword();
    }

    /** Returns a data source that logs in as a service's role, to hold what V1 grants it. */
    public DataSource dataSourceAs(final DatabaseRole role) {
        final PGSimpleDataSource source = dataSource(running, name);
        source.setUser(role.roleName());
        source.setPassword(password(role));
        return source;
    }

    /**
     * Runs one statement as a role over the container's own socket, the only way the backup role logs in.
     *
     * @return psql's error output, empty when the statement went through
     */
    public String socketAs(final DatabaseRole role, final String sql) {
        try {
            return running.execInContainer(
                            "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", role.roleName(), "-d", name, "-c", sql)
                    .getStderr();
        } catch (final java.io.IOException | InterruptedException failure) {
            throw new IllegalStateException("psql did not run in the test container", failure);
        }
    }

    /**
     * Migrates this database up to a version, for a test of a database an older release left behind.
     *
     * @param placeholders the role names, which an older release may have named differently
     * @param target a version such as {@code "2"}, or {@code "latest"}
     */
    public void migrate(final Map<String, String> placeholders, final String target) {
        migrate(dataSource, placeholders, target);
    }

    private static void migrate(final DataSource source, final Map<String, String> placeholders, final String target) {
        Flyway.configure(TestDatabase.class.getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(placeholders)
                .target(target)
                .load()
                .migrate();
    }

    /** The password every role gets in the test container: its own key. */
    private static String password(final DatabaseRole role) {
        return role.key();
    }

    /** Starts and migrates on first use; the container outlives every test and goes with the JVM. */
    private static final class Container {

        static final PostgreSQLContainer<?> RUNNING = start();

        private static PostgreSQLContainer<?> start() {
            final PostgreSQLContainer<?> running = new PostgreSQLContainer<>(IMAGE).withDatabaseName(TEMPLATE);
            running.start();
            final Map<DatabaseRole, String> passwords = new EnumMap<>(DatabaseRole.class);
            for (final DatabaseRole role : DatabaseRole.values()) {
                passwords.put(role, password(role));
            }
            try {
                DatabaseRole.provision(dataSource(running, TEMPLATE), DatabaseRole.PREFIX, passwords);
            } catch (final SQLException failure) {
                throw new IllegalStateException("could not create the roles V1 grants to", failure);
            }
            migrate(dataSource(running, TEMPLATE), DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");
            return running;
        }
    }

    private static PGSimpleDataSource dataSource(final PostgreSQLContainer<?> running, final String database) {
        final PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl("jdbc:postgresql://" + running.getHost() + ":" + running.getMappedPort(5432) + "/" + database);
        source.setUser(running.getUsername());
        source.setPassword(running.getPassword());
        return source;
    }

    // Run from the maintenance database: CREATE DATABASE refuses a template anyone is connected to.
    private static void execute(final PostgreSQLContainer<?> running, final String sql) {
        try (Connection connection = dataSource(running, "postgres").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql + " failed", failure);
        }
    }
}
