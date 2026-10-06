package eu.nordtal.season.shippedjars;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.TestDatabase;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every jar a release ships, started as far as a build can: loaded by its host's rules, then on a database. */
class ShippedJarsTest {

    @ParameterizedTest
    @ValueSource(strings = {"smp", "limbo", "hunger-games", "proxy", "discord-bot", "steward", "steward-agent"})
    void everyClassOurCodeNamesIsInTheJarOrItsHost(final String name) throws Exception {
        try (ShippedJar jar = ShippedJar.named(name)) {
            assertEquals(List.of(), jar.missingClasses(), name + " names classes that neither it nor its host carries");
            assertEquals(
                    List.of(),
                    jar.missingEntryPoints(),
                    name + " names an entry class that neither it nor its host carries");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"smp", "limbo", "hunger-games", "proxy", "discord-bot", "steward", "steward-agent"})
    void theJarOpensItsDatabasePoolOnAFreshPostgres(final String name) throws Exception {
        final TestDatabase database = TestDatabase.fresh();
        try (ShippedJar jar = ShippedJar.named(name)) {
            jar.queriesThrough(database.jdbcUrl(), database.username(), database.password());
        }
    }
}
