package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Binds and reads both id types against a real PostgreSQL, which alone says whether a UUID lands as one. */
class JdbisIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static Jdbi jdbi;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon reachable - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        final PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbi = Jdbis.over(dataSource);
        jdbi.useHandle(handle -> handle.execute("CREATE TABLE link (discord_id varchar(32), mc_uuid uuid)"));
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    record Link(DiscordId discordId, PlayerId mcUuid) {}

    @Test
    void bothIdsBindAndReadBackAsTheirColumnTypes() {
        final Link link = new Link(
                DiscordId.of("100000000000000001"),
                PlayerId.of(UUID.fromString("11111111-1111-1111-1111-111111111111")));

        jdbi.useHandle(handle -> handle.createUpdate("INSERT INTO link VALUES (:discord, :player)")
                .bind("discord", link.discordId())
                .bind("player", link.mcUuid())
                .execute());

        final Link read = jdbi.withHandle(handle -> handle.createQuery("SELECT discord_id, mc_uuid FROM link")
                .map((results, context) -> new Link(
                        context.findColumnMapperFor(DiscordId.class)
                                .orElseThrow()
                                .map(results, 1, context),
                        context.findColumnMapperFor(PlayerId.class)
                                .orElseThrow()
                                .map(results, 2, context)))
                .one());
        assertEquals(link, read);
        assertEquals(
                "uuid",
                jdbi.withHandle(handle -> handle.createQuery("SELECT pg_typeof(mc_uuid)::text FROM link")
                        .mapTo(String.class)
                        .one()));
    }
}
