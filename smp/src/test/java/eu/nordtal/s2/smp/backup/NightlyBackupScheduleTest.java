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
    @DisplayName("a blank time is 'never', and it is the only thing that is")
    void blankMeansNever() {
        // The local stack sets it blank because there is no Arcane on a laptop, so the run would
        // fail every night at a quarter to five. Everything else has to be an error rather than a
        // quiet "never" - a schedule that turns itself off is a backup nobody knows they lost.
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("quarter to five"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("4:45pm"));
        assertThrows(IllegalArgumentException.class, () -> DailySchedule.parse("25:00"));
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
}
