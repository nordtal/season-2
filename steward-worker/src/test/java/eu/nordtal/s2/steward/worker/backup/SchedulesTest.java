package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * A schedule saved in Steward has to reach the clocks without a restart.
 *
 * <p>The config here is a live object whose values change between two {@link Schedules#arm()}
 * calls, which is exactly what a {@code ConfigHandle} reload does to the instance the worker
 * holds.</p>
 */
class SchedulesTest {

    /** What the two sections say right now; changed by the test between arms. */
    private String backupAt = "04:45";
    private String updateAt = "";

    @Test
    @DisplayName("arming again picks up a schedule that changed since the last arm")
    void armingAgainReadsTheConfigAgain() {
        try (Schedules schedules = new Schedules(noDirectory(), config(), ZoneId.of("Europe/Berlin"))) {
            schedules.arm();
            assertArrayEquals(new boolean[]{true, false}, schedules.running(),
                    "by default only the backup runs on a clock");

            updateAt = "03:30";
            backupAt = "";
            schedules.arm();
            assertArrayEquals(new boolean[]{false, true}, schedules.running(),
                    "the saved schedule has to be the one that is armed now");
        }
    }

    /** A StewardSpec whose two schedule sections read the fields above; everything else defaults. */
    private StewardSpec config() {
        final StewardSpec.BackupSpec backup = section(StewardSpec.BackupSpec.class,
                (proxy, method, args) -> method.getName().equals("at") ? backupAt
                        : InvocationHandler.invokeDefault(proxy, method, args));
        final StewardSpec.UpdateSpec update = section(StewardSpec.UpdateSpec.class,
                (proxy, method, args) -> method.getName().equals("at") ? updateAt
                        : method.getName().equals("days") ? List.of("SUNDAY")
                        : InvocationHandler.invokeDefault(proxy, method, args));
        return section(StewardSpec.class, (proxy, method, args) -> switch (method.getName()) {
            case "backup" -> backup;
            case "update" -> update;
            default -> InvocationHandler.invokeDefault(proxy, method, args);
        });
    }

    private static <T> T section(final Class<T> type, final InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    /** Arming never writes a row - only firing does, and nothing here waits long enough to fire. */
    private static UpdateDirectory noDirectory() {
        return section(UpdateDirectory.class, (proxy, method, args) -> {
            throw new AssertionError("the clock asked the database: " + method.getName());
        });
    }
}
