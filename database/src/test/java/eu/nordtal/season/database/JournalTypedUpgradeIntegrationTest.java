package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/** Holds what typing the journal does to the lines an installation already wrote. */
class JournalTypedUpgradeIntegrationTest {

    @Test
    void everyOldLineKeepsItsActorItsPersonAndItsSentence() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "13");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO audit_log (occurred, action, actor, subject, detail) VALUES
                    (now() - interval '5 minutes', 'GRANT_ACCESS', '100000000000000009', '100000000000000001', '30 days'),
                    (now() - interval '4 minutes', 'FORGET_FACTORS', 'host', '100000000000000001', NULL),
                    (now() - interval '3 minutes', 'RECREATE', 'agent', 'smp', 'recreated'),
                    (now() - interval '2 minutes', 'CANCEL_RUN', '100000000000000009', '145', NULL),
                    (now() - interval '1 minutes', 'SET_PHASE', NULL, NULL, 'SMP -> MAINTENANCE');
                """));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "14");

        assertEquals(
                List.of(
                        "GRANT_ACCESS PERSON 100000000000000009 100000000000000001 {\"detail\": \"30 days\"}",
                        "FORGET_FACTORS HOST - 100000000000000001 {}",
                        "RECREATE STEWARD - - {\"detail\": \"recreated\", \"target\": \"smp\"}",
                        "CANCEL_RUN PERSON 100000000000000009 - {\"target\": \"145\"}",
                        "SET_PHASE STEWARD - - {\"detail\": \"SMP -> MAINTENANCE\"}"),
                jdbi.withHandle(
                        handle -> handle.createQuery("""
                                SELECT action || ' ' || actor_kind || ' ' || coalesce(actor_id, '-') || ' '
                                    || coalesce(subject, '-') || ' ' || facts::text
                                FROM audit_log ORDER BY occurred
                                """).mapTo(String.class).list()),
                "a Discord id is a person, the host stays the host, anything else is Steward, and a subject"
                        + " that names no person becomes the target");
    }
}
