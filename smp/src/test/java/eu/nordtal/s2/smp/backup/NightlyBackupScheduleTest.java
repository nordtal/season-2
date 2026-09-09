package eu.nordtal.s2.smp.backup;

import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.farm.DailySchedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the network's backup is asked for, and why it is asked for from here at all.
 *
 * <h2>What this can and cannot say</h2>
 * {@link NightlyBackup} itself needs a Bukkit scheduler and a database, so nothing here starts one.
 * What is testable is the part that is arithmetic and configuration - and that half is where the
 * mistakes are: a time that silently becomes "never", or a backup scheduled <em>after</em> the farm
 * world reset it is supposed to precede. Both would look exactly like a working backup until
 * somebody went looking in Arcane for a snapshot that was never taken.
 */
class NightlyBackupScheduleTest {

    /** The spec's own defaults, served by the proxy jcore builds for a fresh file. */
    private static final SmpSpec DEFAULTS = new SmpSpec() {
    };

    @Test
    @DisplayName("the backup is asked for before the farm world reset, not after it")
    void theBackupComesFirst() {
        final LocalTime backup = LocalTime.parse(DEFAULTS.backupTime());
        final LocalTime reset = LocalTime.parse(DEFAULTS.farmResetTime());

        assertTrue(backup.isBefore(reset),
                "backup-time (" + backup + ") has to run before farm-reset-time (" + reset + ")."
                        + " The other way round snapshots a farm world that is about to be deleted,"
                        + " on servers the backup had only just brought back up.");
        assertTrue(java.time.Duration.between(backup, reset).toMinutes() >= 10,
                "and with room between them: the backup stops smp, network-control and the bot,"
                        + " and the reset must not begin while they are still coming back");
    }

    @Test
    @DisplayName("only a blank time means 'never' - every other unreadable value is an error")
    void onlyBlankMeansNever() {
        // The local stack sets it blank because there is no Arcane on a laptop, so the run would
        // fail every night at a quarter to five. Everything else has to be an error rather than a
        // quiet "never" - a schedule that turns itself off is a backup nobody knows they lost.
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("quarter to five"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("4:45pm"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("25:00"));
    }

    @Test
    @DisplayName("a blank or absent time builds a backup that schedules nothing")
    void aBlankTimeBuildsNoSchedule() {
        // The branch above proves what the parser does; this proves what NightlyBackup does with
        // it, which is the half an operator actually meets. Both nulls are deliberate: nothing on
        // this path touches the plugin or the directory, so a blank time that reached either of
        // them would fail here rather than at a quarter to five on a server.
        for (final String blank : new String[]{null, "", "   "}) {
            final NightlyBackup backup = new NightlyBackup(quietPlugin(), noDirectory(),
                    Runnable::run, blank);

            assertTrue(backup.at().isEmpty(), "a blank time still produced a schedule: " + blank);
            // start() must reach neither the Bukkit scheduler nor the database. The stand-ins
            // below answer only getLogger(); anything else throws, so this call is the assertion.
            backup.start();
            backup.stop();
        }
    }

    @Test
    @DisplayName("the wait is measured to the next occurrence, over midnight included")
    void theWaitIsToTheNextOne() {
        final DailySchedule schedule = DailySchedule.parse(DEFAULTS.backupTime());

        assertEquals(java.time.Duration.ofMinutes(45),
                schedule.until(LocalTime.of(4, 0)),
                "before it, today");
        assertEquals(java.time.Duration.ofHours(23).plusMinutes(45),
                schedule.until(LocalTime.of(5, 0)),
                "after it, tomorrow - the case a server started at any other hour of the day hits");
    }

    /**
     * A {@code Plugin} that can be logged to and nothing else.
     *
     * <p>A proxy rather than a mocking library, because what is being asserted is that
     * {@link NightlyBackup#start()} touches nothing: every method except {@code getLogger} throws,
     * so a future version that reached for the scheduler would fail here rather than on a server.
     */
    private static org.bukkit.plugin.Plugin quietPlugin() {
        final java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger("nightly-backup-test");
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                NightlyBackupScheduleTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> {
                    if ("getLogger".equals(method.getName())) {
                        return logger;
                    }
                    if ("toString".equals(method.getName())) {
                        return "quiet plugin";
                    }
                    throw new AssertionError("a backup with no schedule called Plugin#"
                            + method.getName());
                });
    }

    /** An {@code UpdateDirectory} that refuses every call, for the same reason. */
    private static eu.nordtal.s2.common.update.UpdateDirectory noDirectory() {
        return (eu.nordtal.s2.common.update.UpdateDirectory) java.lang.reflect.Proxy.newProxyInstance(
                NightlyBackupScheduleTest.class.getClassLoader(),
                new Class<?>[]{eu.nordtal.s2.common.update.UpdateDirectory.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "no directory";
                    }
                    throw new AssertionError("a backup with no schedule called UpdateDirectory#"
                            + method.getName());
                });
    }

}
