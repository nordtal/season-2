package eu.nordtal.s2.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.setting.SettingStore;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** That a Paper process publishes its group under its own role, and takes an admin's change from Steward live. */
class DatabaseSettingsIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSettingsIntegrationTest.class);

    @Test
    void aChangeStewardMakesReachesTheProcessOnTheSignal() throws Exception {
        final TestDatabase database = TestDatabase.fresh();
        final DatabaseSettings settings = DatabaseSettings.over(
                SettingStore.using(database.dataSourceAs(DatabaseRole.SMP)),
                "smp",
                Environment.of("NORDTAL_TEST").reading(variable -> null),
                LOGGER);
        final Setting<ExampleSpec> example =
                settings.load(Group.of("example", ExampleSpec.class).whileRunning());
        assertEquals("nordtal", example.get().name());

        final Semaphore changed = new Semaphore(0);
        try (SignalHub hub = SignalHub.open(
                database.jdbcUrl(), database.username(), database.password(), 5, "settings-test", LOGGER)) {
            settings.listen(hub, () -> {
                try {
                    example.reload();
                } catch (final SettingsException refused) {
                    throw new IllegalStateException(refused);
                }
                changed.release();
            });
            hub.start();
            SettingStore.using(database.dataSourceAs(DatabaseRole.STEWARD))
                    .change("smp", "example", Map.of("name", "\"from-steward\""), Actor.STEWARD, current -> true);

            assertTrue(changed.tryAcquire(30, TimeUnit.SECONDS), "the change never reached the process");
        }
        assertEquals("from-steward", example.get().name());
    }
}
