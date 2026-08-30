package eu.nordtal.s2.paymentsbot.persistence.dao;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.paymentsbot.persistence.model.Contribution;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the DAO layer against a real PostgreSQL instance.
 * <p>
 * Testcontainers is driven by hand from {@link BeforeAll} / {@link AfterAll} rather than through
 * the {@code @Testcontainers} JUnit extension: that extension ships in
 * {@code org.testcontainers:junit-jupiter}, which is compiled against JUnit 5, and this repo is on
 * the JUnit 6 BOM. Skipping when no Docker daemon is reachable is done with an assumption, which
 * is what that extension's {@code disabledWithoutDocker} does anyway.
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContributionRepositoryIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static Database database;

    private ContributionRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed DAO tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("payments")
                .withUsername("payments")
                .withPassword("payments");
        postgres.start();

        database = Database.create(DatabaseConfig.of(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    @BeforeEach
    void migrateAndTruncate() {
        // migrate() is idempotent, so running it per test also asserts that a second start of the
        // bot against an already-migrated database is a no-op rather than a Flyway failure.
        database.migrate();
        database.jdbi().useHandle(handle -> handle.execute("TRUNCATE TABLE contribution"));
        repository = new ContributionRepository(database);
    }

    @Test
    void migrationCreatesTheTableAndIsIdempotent() {
        // The very first migrate() ran in @BeforeEach and applied V1; a second one applies nothing.
        assertEquals(0, database.migrate());

        final Integer columns = database.jdbi().withHandle(handle -> handle
                .createQuery("SELECT count(*) FROM information_schema.columns WHERE table_name = 'contribution'")
                .mapTo(Integer.class)
                .one());
        assertEquals(6, columns);
    }

    @Test
    void euroAmountColumnIsNumericNotFloat() {
        final String type = database.jdbi().withHandle(handle -> handle
                .createQuery("""
                        SELECT data_type FROM information_schema.columns
                        WHERE table_name = 'contribution' AND column_name = 'euro_amount'
                        """)
                .mapTo(String.class)
                .one());
        assertEquals("numeric", type);
    }

    @Test
    void insertReturnsTheGeneratedUuidAndWritesItBack() {
        final Contribution contribution = contribution("receiver", 5f, LocalDateTime.now(), 60, 1L);
        assertNull(contribution.getId(), "precondition: the id is assigned by PostgreSQL");

        final Contribution saved = repository.save(contribution);

        assertNotNull(saved.getId(), "save() must return the generated UUID, not a null id");
        assertSame(contribution, saved, "save() returns the instance it was given");

        final Contribution loaded = repository.findFirstById(saved.getId());
        assertNotNull(loaded);
        assertEquals("receiver", loaded.getReceiverId());
        assertEquals(5f, loaded.getEuroAmount());
        assertEquals(60L, loaded.getDurationSeconds());
        assertEquals(1L, loaded.getBunqPaymentId());
        assertNotNull(loaded.getCreated());
    }

    @Test
    void snakeCaseColumnsMapOntoCamelCaseProperties() {
        // receiver_id -> receiverId, duration_seconds -> durationSeconds, bunq_payment_id ->
        // bunqPaymentId. If the column-name matchers were wrong these would all come back null/0.
        final UUID id = repository.save(contribution("snake", 7f, LocalDateTime.now(), 42, 99L)).getId();

        final Contribution loaded = repository.findFirstById(id);
        assertNotNull(loaded);
        assertEquals("snake", loaded.getReceiverId());
        assertEquals(42L, loaded.getDurationSeconds());
        assertEquals(99L, loaded.getBunqPaymentId());
    }

    @Test
    void moneyIsStoredToTheCent() {
        final UUID id = repository.save(contribution("cents", 12.34f, LocalDateTime.now(), 0, 7L)).getId();

        final BigDecimal stored = database.jdbi().withHandle(handle -> handle
                .createQuery("SELECT euro_amount FROM contribution WHERE id = :id")
                .bind("id", id)
                .mapTo(BigDecimal.class)
                .one());

        assertEquals(0, new BigDecimal("12.34").compareTo(stored), "stored " + stored);
        assertEquals(12.34f, repository.findFirstById(id).getEuroAmount());
    }

    @Test
    void findByReceiverIsOrderedOldestFirstThenHighestAmount() {
        final LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0);

        // Inserted in an order that is neither the expected order nor its reverse.
        repository.save(contribution("alice", 5f, base.plusMinutes(10), 60, 3L));
        repository.save(contribution("alice", 3f, base, 60, 1L));
        repository.save(contribution("alice", 9f, base, 60, 2L));
        repository.save(contribution("bob", 7f, base, 60, 4L));

        final List<Contribution> alice = repository.findByReceiverOrdered("alice");

        assertEquals(3, alice.size(), "bob's row must not leak into alice's result");
        assertEquals(base, alice.get(0).getCreated());
        assertEquals(9f, alice.get(0).getEuroAmount(), "same timestamp: higher amount comes first");
        assertEquals(3f, alice.get(1).getEuroAmount());
        assertEquals(base.plusMinutes(10), alice.get(2).getCreated());
    }

    @Test
    void findActiveByReceiverResolvesAgainstTheStoredRows() {
        // The scheduling logic itself is covered by ContributionRepositoryTest; this only proves
        // the database path feeds it correctly.
        final LocalDateTime now = LocalDateTime.now();
        repository.save(contribution("carol", 3f, now.minusHours(1), 0, 11L));
        repository.save(contribution("carol", 9f, now.minusMinutes(1), 3600, 12L));

        final Contribution active = repository.findActiveByReceiver("carol");
        assertNotNull(active);
        assertEquals(9f, active.getEuroAmount(), "the higher tier is active while it lasts");
    }

    @Test
    void findActiveByReceiverReturnsNullForAnUnknownReceiver() {
        assertNull(repository.findActiveByReceiver("nobody"));
    }

    @Test
    void allProcessedPaymentIdsReturnsEveryBookedPayment() {
        assertEquals(Set.of(), repository.allProcessedPaymentIds(), "the table starts empty");

        repository.save(contribution("dave", 3f, LocalDateTime.now(), 60, 100L));
        repository.save(contribution("dave", 5f, LocalDateTime.now(), 60, 101L));
        repository.save(contribution("erin", 5f, LocalDateTime.now(), 60, -1L));

        assertEquals(Set.of(100L, 101L, -1L), repository.allProcessedPaymentIds());
    }

    @Test
    void aRealBunqPaymentCannotBeBookedTwice() {
        repository.save(contribution("frank", 5f, LocalDateTime.now(), 60, 500L));

        assertThrows(RuntimeException.class,
                () -> repository.save(contribution("frank", 5f, LocalDateTime.now(), 60, 500L)),
                "the partial unique index must reject a duplicate bunq payment id");
    }

    @Test
    void syntheticContributionsMayRepeatTheSentinelId() {
        // /test-con and /manual-con both write -1 and are expected to be usable more than once.
        repository.save(contribution("grace", 3f, LocalDateTime.now(), 60, -1L));
        repository.save(contribution("grace", 5f, LocalDateTime.now(), 60, -1L));

        assertEquals(2, repository.findByReceiverOrdered("grace").size());
    }

    @Test
    void deleteRemovesTheRow() {
        final Contribution saved = repository.save(contribution("heidi", 5f, LocalDateTime.now(), 60, 900L));
        assertNotNull(repository.findFirstById(saved.getId()));

        repository.delete(saved);

        assertNull(repository.findFirstById(saved.getId()));
        assertTrue(repository.all().isEmpty());
    }

    private static Contribution contribution(final String receiverId,
                                             final float euroAmount,
                                             final LocalDateTime created,
                                             final long durationSeconds,
                                             final long bunqPaymentId) {
        final Contribution contribution = new Contribution();
        contribution.setReceiverId(receiverId);
        contribution.setEuroAmount(euroAmount);
        contribution.setCreated(created);
        contribution.setDurationSeconds(durationSeconds);
        contribution.setBunqPaymentId(bunqPaymentId);
        return contribution;
    }
}
