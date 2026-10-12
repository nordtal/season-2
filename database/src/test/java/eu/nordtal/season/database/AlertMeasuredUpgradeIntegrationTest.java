package eu.nordtal.season.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.Measure.Gauge;
import eu.nordtal.season.database.alert.RaisedAlert;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Holds what keeping measurements as typed values does to the alerts an installation already wrote.
 *
 * Each reads its measurements back out of the messages that told them, and an alert that measured nothing keeps none.
 */
class AlertMeasuredUpgradeIntegrationTest {

    /** One alert of every kind that measured something, raised a minute apart, and one that measured nothing. */
    private static final String OLD_ALERTS = """
            INSERT INTO admin_alert (raised_by, type, level, subject, title, detail, path, raised) VALUES
                ('steward', 'SERVICE', 'DOWN', 'migrate',
                    '{"key": "alert.not-running", "args": {"service": {"kind": "text", "value": "migrate"}}}',
                    '[{"key": "alert.docker-state", "args": {"state": {"kind": "text", "value": "exited"}}}]',
                    '/services/migrate', now() - interval '9 minutes'),
                ('steward', 'SERVICE', 'DOWN', 'proxy, migrate',
                    '{"key": "alert.several", "args": {"count": {"kind": "number", "value": 2}}}',
                    '[{"key": "alert.unhealthy", "args": {"service": {"kind": "text", "value": "proxy"}}},
                      {"key": "alert.failed-once", "args": {"service": {"kind": "text", "value": "migrate"},
                                                           "code": {"kind": "number", "value": 3}}}]',
                    '/services/proxy', now() - interval '8 minutes'),
                ('steward', 'DRIFT', 'WARN', 'smp, limbo',
                    '{"key": "alert.older-image", "args": {"count": {"kind": "number", "value": 2}, "services":
                      {"kind": "list", "value": [{"kind": "text", "value": "smp"},
                                                 {"kind": "text", "value": "limbo"}]}}}',
                    '[]', '/operations/updates', now() - interval '7 minutes'),
                ('steward', 'BACKUP', 'DOWN', 'database dump',
                    '{"key": "alert.old-dump", "args": {"hours": {"kind": "number", "value": 40}}}',
                    '[{"key": "alert.permitted-age", "args": {"hours": {"kind": "number", "value": 36}}}]',
                    '/operations/backups', now() - interval '6 minutes'),
                ('steward', 'BACKUP', 'WARN', 'smp-world',
                    '{"key": "alert.old-archive", "args": {"volume": {"kind": "text", "value": "smp-world"},
                                                           "hours": {"kind": "number", "value": 37}}}',
                    '[{"key": "alert.permitted-age", "args": {"hours": {"kind": "number", "value": 36}}}]',
                    '/operations/backups', now() - interval '5 minutes'),
                ('steward', 'BACKUP', 'DOWN', 'offsite copy',
                    '{"key": "alert.old-offsite", "args": {"hours": {"kind": "number", "value": 50}}}',
                    '[{"key": "alert.permitted-age", "args": {"hours": {"kind": "number", "value": 36}}}]',
                    '/operations/backups', now() - interval '4 minutes'),
                ('steward', 'DISK', 'WARN', 'disk',
                    '{"key": "alert.disk", "args": {"percent": {"kind": "number", "value": 91}}}',
                    '[{"key": "alert.threshold", "args": {"percent": {"kind": "number", "value": 85}}}]',
                    '/', now() - interval '3 minutes'),
                ('steward', 'MEMORY', 'WARN', 'memory',
                    '{"key": "alert.memory", "args": {"percent": {"kind": "number", "value": 93}}}',
                    '[{"key": "alert.threshold", "args": {"percent": {"kind": "number", "value": 90}}},
                      {"key": "alert.no-limit", "args": {}}]',
                    '/', now() - interval '2 minutes'),
                ('discord-bot', 'BOT', 'WARN', 'DM', '{"key": "alert.dm", "args": {}}', '[]', '/access',
                    now() - interval '1 minute');
            """;

    @Test
    void anOldAlertKeepsWhatItsMessagesSay() {
        final TestDatabase upgraded = TestDatabase.empty();
        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "43");
        final Jdbi jdbi = Jdbi.create(upgraded.dataSource());
        jdbi.useHandle(handle -> handle.execute(OLD_ALERTS));

        upgraded.migrate(DatabaseRole.placeholders(DatabaseRole.PREFIX), "latest");

        assertEquals(
                List.of(
                        List.of(),
                        List.of(Measure.against(Gauge.MEMORY_PERCENT, "memory", 93, 90)),
                        List.of(Measure.against(Gauge.DISK_PERCENT, "disk", 91, 85)),
                        List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, "offsite copy", 50, 36)),
                        List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, "smp-world", 37, 36)),
                        List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, "database dump", 40, 36)),
                        List.of(
                                Measure.state(Gauge.OLDER_IMAGE, "smp", null),
                                Measure.state(Gauge.OLDER_IMAGE, "limbo", null)),
                        List.of(
                                Measure.state(Gauge.HEALTH, "proxy", "unhealthy"),
                                Measure.of(Gauge.EXIT_CODE, "migrate", 3)),
                        List.of(Measure.state(Gauge.CONTAINER_STATE, "migrate", "exited"))),
                AlertBook.using(upgraded.dataSource()).recent(20).stream()
                        .map(RaisedAlert::alert)
                        .map(alert -> alert.measured())
                        .toList());
    }
}
