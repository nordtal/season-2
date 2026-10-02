package eu.nordtal.s2.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds what giving an added plugin the structured actor does to the plugins an installation already has. */
class PluginActorUpgradeIntegrationTest {

    @Test
    void theIdInTheComposedNameIsThePersonAndAnythingElseIsSteward() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "14");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO service_plugin (service, artifact, project_id, file_prefix, title, added_by) VALUES
                    ('smp', 'a', 'p1', 'a', 'A', 'alex (300000000000000077)'),
                    ('smp', 'b', 'p2', 'b', 'B', 'a name (with words) (100000000000000001)'),
                    ('smp', 'c', 'p3', 'c', 'C', ''),
                    ('smp', 'd', 'p4', 'd', 'D', NULL),
                    ('smp', 'e', 'p5', 'e', 'E', 'till');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "15");

        assertEquals(
                List.of(
                        "a PERSON 300000000000000077",
                        "b PERSON 100000000000000001",
                        "c STEWARD -",
                        "d STEWARD -",
                        "e STEWARD -"),
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT artifact || ' ' || actor_kind || ' ' || coalesce(actor_id, '-')
                                FROM service_plugin ORDER BY artifact
                                """).mapTo(String.class).list()),
                "the Discord id the interface put in parentheses is the person; no id is Steward");
    }
}
