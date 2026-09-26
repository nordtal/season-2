package eu.nordtal.s2.steward.ui.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.SQLException;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@code steward_push_preference} against a real PostgreSQL, and the one rule that is not SQL: a
 * missing row is the type's own default (steward/98, Till's review of 2026-09-18).
 *
 * <p>Modelled on {@link PushSubscriptionsTest}, the table beside it.</p>
 */
class PushPreferencesTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private PushPreferences preferences;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(PushPreferencesTest.class.getClassLoader())
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void freshTable() {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE steward_push_preference");
        } catch (final SQLException failure) {
            throw new RuntimeException(failure);
        }
        preferences = new PushPreferences(dataSource);
    }

    @Test
    @DisplayName("an account that has never chosen gets the defaults, and no row is written for it")
    void anAccountWithNoRowsGetsTheDefaults() {
        final Map<AlertType, Boolean> mine = preferences.of("42");

        // Till, 2026-09-18, verbatim: the critical cases plus disk and memory are on, the rest is
        // off. "The rest" is exactly drift.
        assertEquals(
                Map.of(
                        AlertType.SERVICE, true,
                        AlertType.BACKUP, true,
                        AlertType.DISK, true,
                        AlertType.MEMORY, true,
                        AlertType.DRIFT, false),
                mine,
                "the defaults an account gets before it has ever opened the dialog are not the ones"
                        + " Till asked for");
        assertTrue(
                preferences.all().isEmpty(),
                "reading the preferences of an account wrote a row for it - see V30 on why a"
                        + " missing row has to stay missing");
    }

    @Test
    @DisplayName("a switch is remembered, and only for the account that set it")
    void aSwitchIsScopedToTheAccount() {
        preferences.set("42", AlertType.DRIFT, true);
        preferences.set("42", AlertType.SERVICE, false);

        assertTrue(preferences.of("42").get(AlertType.DRIFT));
        assertFalse(preferences.of("42").get(AlertType.SERVICE));
        assertFalse(preferences.of("43").get(AlertType.DRIFT), "one account's choice leaked into another's");
        assertTrue(preferences.of("43").get(AlertType.SERVICE), "one account's choice leaked into another's");
    }

    @Test
    @DisplayName("setting the same switch twice is one row, not two")
    void settingTwiceIsOneRow() {
        preferences.set("42", AlertType.DISK, false);
        preferences.set("42", AlertType.DISK, true);

        assertEquals(1, preferences.all().get("42").size(), "the same switch set twice produced two rows");
        assertTrue(preferences.of("42").get(AlertType.DISK));
    }

    @Test
    @DisplayName("a switch put back where it started is still a row - chosen is not the same as never looked")
    void choosingTheDefaultIsStillAChoice() {
        preferences.set("42", AlertType.SERVICE, true);

        assertEquals(
                Map.of(AlertType.SERVICE, true),
                preferences.all().get("42"),
                "choosing a value that happens to equal the default deleted the row instead of" + " recording it");
    }

    @Test
    @DisplayName("a stored type this release does not know is ignored rather than fatal")
    void anUnknownTypeIsIgnored() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO steward_push_preference (discord_id, alert_type,"
                    + " enabled, updated_at) VALUES ('42', 'sunspots', true, now())");
        }

        // A background poll that threw on a row a later release had left behind would stop pushing
        // anything at all - see AlertType#of.
        assertEquals(5, preferences.of("42").size());
        assertTrue(
                preferences.all().get("42") == null
                        || preferences.all().get("42").isEmpty(),
                "a type nobody knows was carried into the effective answer");
    }
}
