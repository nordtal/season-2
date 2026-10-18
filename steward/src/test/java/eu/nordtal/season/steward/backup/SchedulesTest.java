package eu.nordtal.season.steward.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import eu.nordtal.season.common.time.ManualScheduler;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.steward.config.BackupSpec;
import eu.nordtal.season.steward.config.StewardSpec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests that {@link Schedules#arm()} applies a changed schedule without a restart. */
class SchedulesTest {

    /** The values of the two sections, which the test changes between arms. */
    private String backupAt = "04:45";

    private String updateAt = "";

    @Test
    void armingAgainPicksUpAScheduleThatChangedSinceTheLastArm() {
        try (Schedules schedules = new Schedules(
                noDirectory(), config(), Clock.system(ZoneId.of("Europe/Berlin")), new ManualScheduler())) {
            schedules.arm();
            assertArrayEquals(
                    new boolean[] {true, false}, schedules.running(), "by default only the backup runs on a clock");

            updateAt = "03:30";
            backupAt = "";
            schedules.arm();
            assertArrayEquals(
                    new boolean[] {false, true},
                    schedules.running(),
                    "the saved schedule has to be the one that is armed now");
        }
    }

    /** Returns a StewardSpec whose schedule sections read the fields above. */
    private StewardSpec config() {
        final BackupSpec backup = section(
                BackupSpec.class,
                (proxy, method, args) -> method.getName().equals("at")
                        ? backupAt
                        : InvocationHandler.invokeDefault(proxy, method, args));
        final StewardSpec.UpdateSpec update = section(
                StewardSpec.UpdateSpec.class,
                (proxy, method, args) -> method.getName().equals("at")
                        ? updateAt
                        : method.getName().equals("days")
                                ? List.of("SUNDAY")
                                : InvocationHandler.invokeDefault(proxy, method, args));
        return section(StewardSpec.class, (proxy, method, args) -> switch (method.getName()) {
            case "backup" -> backup;
            case "update" -> update;
            default -> InvocationHandler.invokeDefault(proxy, method, args);
        });
    }

    private static <T> T section(final Class<T> type, final InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    /** Tests that arming writes no row. */
    private static UpdateDirectory noDirectory() {
        return section(UpdateDirectory.class, (proxy, method, args) -> {
            throw new AssertionError("the clock asked the database: " + method.getName());
        });
    }
}
