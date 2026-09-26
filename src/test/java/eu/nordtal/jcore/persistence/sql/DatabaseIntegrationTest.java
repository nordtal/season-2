package eu.nordtal.jcore.persistence.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.statement.UseRowMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives {@link Database} against a real PostgreSQL server started by Testcontainers.
 * <p>
 * {@code @Testcontainers} disables the whole class when no Docker daemon is reachable, so
 * {@code ./gradlew build} still passes on machines and CI runners without Docker (JitPack, for
 * one) - the class is reported as skipped instead of failing.
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
public class DatabaseIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static Database database;
    private static int migrationsAppliedOnFirstRun;

    /** A widget row. */
    public record Widget(long id, String name, UUID owner) {}

    /** Maps a {@code widgets} row onto {@link Widget}. */
    public static class WidgetMapper implements RowMapper<Widget> {
        @Override
        public Widget map(final ResultSet rs, final StatementContext ctx) throws SQLException {
            return new Widget(rs.getLong("id"), rs.getString("name"), rs.getObject("owner", UUID.class));
        }
    }

    /** The typed SqlObject DAO that replaces the old string-keyed {@code EntityRepository}. */
    public interface WidgetDao {

        @SqlUpdate("INSERT INTO widgets (name, owner) VALUES (:name, :owner)")
        @GetGeneratedKeys("id")
        long insert(@Bind("name") String name, @Bind("owner") UUID owner);

        @SqlQuery("SELECT id, name, owner FROM widgets WHERE id = :id")
        @UseRowMapper(WidgetMapper.class)
        Optional<Widget> findById(@Bind("id") long id);

        @SqlQuery("SELECT id, name, owner FROM widgets ORDER BY id")
        @UseRowMapper(WidgetMapper.class)
        List<Widget> all();

        @SqlUpdate("DELETE FROM widgets")
        int deleteAll();
    }

    /** Exercises the optional {@link JdbiRepository} base class. */
    static final class WidgetRepository extends JdbiRepository<WidgetDao> {
        WidgetRepository(final Database database) {
            super(database, WidgetDao.class);
        }

        WidgetDao widgets() {
            return dao();
        }
    }

    @BeforeAll
    static void openDatabase() {
        database = Database.create(DatabaseConfig.builder(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .poolName("jcore-it")
                .maximumPoolSize(4)
                .minimumIdle(1)
                .logSql(true)
                .build());

        // Picks up src/test/resources/db/migration/V1__create_widgets.sql
        migrationsAppliedOnFirstRun = database.migrate();
    }

    @AfterAll
    static void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void truncate() {
        database.jdbi().onDemand(WidgetDao.class).deleteAll();
    }

    @Test
    void flywayAppliedTheMigrationAndIsIdempotent() {
        assertEquals(1, migrationsAppliedOnFirstRun, "expected exactly one migration to be applied");
        assertEquals(0, database.migrate(), "a second migrate() must be a no-op");
    }

    @Test
    void readsAndWritesThroughATypedDao() {
        final WidgetRepository repository = new WidgetRepository(database);
        final UUID owner = UUID.randomUUID();

        final long id = repository.widgets().insert("gizmo", owner);
        assertTrue(id > 0, "generated key must reach the caller (the pre-2.0 save() dropped it)");

        final Optional<Widget> found = repository.widgets().findById(id);
        assertTrue(found.isPresent());
        assertEquals("gizmo", found.get().name());
        assertEquals(owner, found.get().owner(), "uuid columns must map to java.util.UUID");

        assertEquals(1, repository.widgets().all().size());
    }

    @Test
    void transactionsRollBack() {
        final WidgetDao dao = database.jdbi().onDemand(WidgetDao.class);

        assertThrows(
                IllegalStateException.class,
                () -> database.jdbi().useTransaction(handle -> {
                    handle.attach(WidgetDao.class).insert("doomed", UUID.randomUUID());
                    throw new IllegalStateException("boom");
                }));

        assertEquals(0, dao.all().size(), "the failed transaction must not have committed");
    }

    @Test
    void poolIsExposedAndClosingReleasesIt() {
        assertNotNull(database.dataSource());

        try (Database throwaway = Database.create(DatabaseConfig.builder(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .poolName("jcore-it-throwaway")
                .maximumPoolSize(1)
                .minimumIdle(0)
                .build())) {
            final int one = throwaway
                    .jdbi()
                    .withHandle(handle ->
                            handle.createQuery("SELECT 1").mapTo(Integer.class).one());
            assertEquals(1, one);
        }
    }
}
