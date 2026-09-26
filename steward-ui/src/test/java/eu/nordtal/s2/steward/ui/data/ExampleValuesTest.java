package eu.nordtal.s2.steward.ui.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The example values an editor fills placeholders with: real rows first, the admin's own before
 * anybody else's, and a fixed value per type when the table is empty.
 */
class ExampleValuesTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure(ExampleValuesTest.class.getClassLoader())
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
    void emptyTables() {
        sql("TRUNCATE account_link, discord_user, hg_member, hg_team, hg_game, smp_milestone CASCADE");
    }

    @Test
    @DisplayName("with nothing in the database every type still has a value")
    void everyTypeHasAFallback() {
        final Map<String, Map<String, String>> examples = new ExampleValues(dataSource).of("1", "Ada");

        assertEquals(Map.of("name", "Steve"), examples.get("player"));
        assertEquals(Map.of("name", "@Ada"), examples.get("discord-member"));
        assertEquals(Map.of("name", "Nordlichter"), examples.get("team"));
        assertEquals(Map.of("name", "smp"), examples.get("service"));
        assertEquals(Map.of("name", "frontier"), examples.get("milestone"));
        assertEquals(Map.of("number", "2"), examples.get("season"));
    }

    @Test
    @DisplayName("the admin's own account is the player, before anybody else's")
    void theAdminIsThePlayer() {
        sql("INSERT INTO discord_user (discord_id, discord_display_name) VALUES ('1', 'Till H'), ('9', NULL)");
        sql("INSERT INTO account_link (discord_id, mc_uuid, mc_name) VALUES"
                + " ('9', '00000000-0000-0000-0000-000000000009', 'Other'),"
                + " ('1', '00000000-0000-0000-0000-000000000001', 'Till')");

        final Map<String, Map<String, String>> examples = new ExampleValues(dataSource).of("1", "Ada");
        assertEquals(Map.of("name", "Till"), examples.get("player"));
        assertEquals(Map.of("name", "@Till H"), examples.get("discord-member"));

        assertEquals(
                Map.of("name", "Other"),
                new ExampleValues(dataSource).of("5", "Ada").get("player"),
                "an admin without a link gets a real player rather than the fallback");
    }

    @Test
    @DisplayName("the team is a real one and the milestone the active one")
    void realTeamAndActiveMilestone() {
        sql("INSERT INTO smp_milestone (key, state) VALUES ('foothold', 'UNLOCKED'), ('nether', 'ACTIVE')");
        sql("INSERT INTO hg_game (id) VALUES ('00000000-0000-0000-0000-00000000000a')");
        sql("INSERT INTO hg_team (id, game_id, name) VALUES ('00000000-0000-0000-0000-00000000000b',"
                + " '00000000-0000-0000-0000-00000000000a', 'Eisbären')");

        final Map<String, Map<String, String>> examples = new ExampleValues(dataSource).of("1", "Ada");
        assertEquals(Map.of("name", "Eisbären"), examples.get("team"));
        assertEquals(Map.of("name", "nether"), examples.get("milestone"));
    }

    private static void sql(final String statement) {
        try (var connection = dataSource.getConnection();
                var sql = connection.createStatement()) {
            sql.execute(statement);
        } catch (final SQLException failure) {
            throw new RuntimeException(failure);
        }
    }
}
