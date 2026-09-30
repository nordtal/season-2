package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
    private final String username;
    private final String password;

    private TestDatabase(final PGSimpleDataSource dataSource, final PostgreSQLContainer<?> running) {
        this.dataSource = dataSource;
        this.username = running.getUsername();
        this.password = running.getPassword();
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

    private static TestDatabase create(final String template) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon reachable");
        final PostgreSQLContainer<?> running = Container.RUNNING;
        final String name = "test_" + CLONES.incrementAndGet();
        execute(running, "CREATE DATABASE " + name + " " + template);
        return new TestDatabase(dataSource(running, name), running);
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
        return username;
    }

    /** Returns the user's password. */
    public String password() {
        return password;
    }

    /** Starts and migrates on first use; the container outlives every test and goes with the JVM. */
    private static final class Container {

        static final PostgreSQLContainer<?> RUNNING = start();

        private static PostgreSQLContainer<?> start() {
            final PostgreSQLContainer<?> running = new PostgreSQLContainer<>(IMAGE).withDatabaseName(TEMPLATE);
            running.start();
            Flyway.configure(TestDatabase.class.getClassLoader())
                    .dataSource(dataSource(running, TEMPLATE))
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
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
