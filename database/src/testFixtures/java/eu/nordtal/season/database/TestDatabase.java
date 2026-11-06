package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A database with the schema applied, for any test that needs PostgreSQL.
 *
 * One server serves the whole test JVM and migrates once; every {@link #fresh()} is a new database cloned from it. The
 * server is a container Testcontainers starts, or the one {@value #HOST} names, as the Steward UI stack does.
 */
public final class TestDatabase {

    /** The image, held against {@code compose.yml}'s default by {@code TestDatabaseImageTest}. */
    public static final String IMAGE = "postgres:17-alpine";

    /** The migrated database every fresh one is cloned from; nothing connects to it after the migration. */
    private static final String TEMPLATE = "schema";

    /**
     * The variable naming a running server to use instead of a container, with {@code NORDTAL_TEST_POSTGRES_PORT},
     * {@code _USER} and {@code _PASSWORD} beside it. Each start drops what an earlier one left there.
     */
    public static final String HOST = "NORDTAL_TEST_POSTGRES_HOST";

    private static final AtomicInteger CLONES = new AtomicInteger();

    private final PGSimpleDataSource dataSource;
    private final Server running;
    private final String name;

    private TestDatabase(final PGSimpleDataSource dataSource, final Server running, final String name) {
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
        restored.running.restore(dump, restored.name);
        restored.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");
        return restored;
    }

    private static TestDatabase create(final String template) {
        if (System.getenv(HOST) == null) {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon reachable");
        }
        final Server running = Template.RUNNING;
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

    /** Returns the user, a superuser of the test server. */
    public String username() {
        return running.username();
    }

    /** Returns the user's password. */
    public String password() {
        return running.password();
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
        return running.socketAs(role, name, sql);
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

    /** The password every role gets on the test server: its own key. */
    private static String password(final DatabaseRole role) {
        return role.key();
    }

    /** Provisions the roles and migrates the template on first use; the server outlives every test. */
    private static final class Template {

        static final Server RUNNING = start();

        private static Server start() {
            final @Nullable String host = System.getenv(HOST);
            final Server running = host == null ? Contained.start() : External.over(host);
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

    /** A PostgreSQL server that holds the template and its clones. */
    private interface Server {

        String host();

        int port();

        String username();

        String password();

        /** Restores a {@code pg_dump -Fc} file into a database of this server. */
        void restore(java.nio.file.Path dump, String database);

        /** Runs one statement as a role over the server's own socket; returns psql's error output. */
        String socketAs(DatabaseRole role, String database, String sql);
    }

    /** The container Testcontainers starts, created with the template database; it goes with the JVM. */
    private record Contained(PostgreSQLContainer<?> container) implements Server {

        static Contained start() {
            final PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE).withDatabaseName(TEMPLATE);
            container.start();
            return new Contained(container);
        }

        @Override
        public String host() {
            return container.getHost();
        }

        @Override
        public int port() {
            return container.getMappedPort(5432);
        }

        @Override
        public String username() {
            return container.getUsername();
        }

        @Override
        public String password() {
            return container.getPassword();
        }

        @Override
        public void restore(final java.nio.file.Path dump, final String database) {
            final String inside = "/tmp/" + database + ".dump";
            container.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(dump), inside);
            try {
                final org.testcontainers.containers.Container.ExecResult result = container.execInContainer(
                        "pg_restore", "--exit-on-error", "-U", username(), "-d", database, inside);
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("pg_restore refused " + dump + ": " + result.getStderr());
                }
            } catch (final java.io.IOException | InterruptedException failure) {
                throw new IllegalStateException("pg_restore did not run in the test container", failure);
            }
        }

        @Override
        public String socketAs(final DatabaseRole role, final String database, final String sql) {
            try {
                return container
                        .execInContainer(
                                "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", role.roleName(), "-d", database,
                                "-c", sql)
                        .getStderr();
            } catch (final java.io.IOException | InterruptedException failure) {
                throw new IllegalStateException("psql did not run in the test container", failure);
            }
        }
    }

    /**
     * A server this JVM did not start, such as the Steward UI stack's; restores run this machine's {@code pg_restore}.
     *
     * Its start drops the template and every clone an earlier JVM left, then creates the template again.
     */
    private record External(String host, int port, String username, String password) implements Server {

        static External over(final String host) {
            final External server = new External(
                    host,
                    Integer.parseInt(environment("NORDTAL_TEST_POSTGRES_PORT", "5432")),
                    environment("NORDTAL_TEST_POSTGRES_USER", "postgres"),
                    environment("NORDTAL_TEST_POSTGRES_PASSWORD", "postgres"));
            final java.util.List<String> left = new java.util.ArrayList<>();
            try (Connection connection = dataSource(server, "postgres").getConnection();
                    Statement statement = connection.createStatement();
                    java.sql.ResultSet rows = statement.executeQuery("SELECT datname FROM pg_database WHERE datname = '"
                            + TEMPLATE + "' OR datname LIKE 'test\\_%'")) {
                while (rows.next()) {
                    left.add(rows.getString(1));
                }
            } catch (final SQLException failure) {
                throw new IllegalStateException("could not list the databases on " + host, failure);
            }
            for (final String database : left) {
                execute(server, "DROP DATABASE \"" + database + "\" WITH (FORCE)");
            }
            execute(server, "CREATE DATABASE " + TEMPLATE);
            return server;
        }

        private static String environment(final String name, final String fallback) {
            final @Nullable String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }

        @Override
        public void restore(final java.nio.file.Path dump, final String database) {
            final ProcessBuilder command = new ProcessBuilder(
                            "pg_restore", "--exit-on-error", "-h", host, "-p", Integer.toString(port), "-U", username,
                            "-d", database, dump.toString())
                    .redirectErrorStream(true);
            command.environment().put("PGPASSWORD", password);
            try {
                final Process process = command.start();
                final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (process.waitFor() != 0) {
                    throw new IllegalStateException("pg_restore refused " + dump + ": " + output);
                }
            } catch (final java.io.IOException | InterruptedException failure) {
                throw new IllegalStateException("pg_restore did not run on this machine", failure);
            }
        }

        @Override
        public String socketAs(final DatabaseRole role, final String database, final String sql) {
            throw new UnsupportedOperationException("the socket of " + host + " is not on this machine");
        }
    }

    private static PGSimpleDataSource dataSource(final Server running, final String database) {
        final PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl("jdbc:postgresql://" + running.host() + ":" + running.port() + "/" + database);
        source.setUser(running.username());
        source.setPassword(running.password());
        return source;
    }

    // Run from the maintenance database: CREATE DATABASE refuses a template anyone is connected to.
    private static void execute(final Server running, final String sql) {
        try (Connection connection = dataSource(running, "postgres").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql + " failed", failure);
        }
    }
}
