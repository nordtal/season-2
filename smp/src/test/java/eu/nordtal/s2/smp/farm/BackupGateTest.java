package eu.nordtal.s2.smp.farm;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.common.update.UpdateStatus;
import eu.nordtal.s2.smp.config.SmpSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the farm world reset asks before it deletes a world, and what it does with the answer.
 *
 * <h2>What this can and cannot say</h2>
 * Whether a row in {@code update_request} really is a backup - finished, successful, and with a
 * volume in it - is decided in {@code :common} and proved against a real PostgreSQL by
 * {@code UpdateDirectoryIntegrationTest}. What is left here is the half this plugin owns: the
 * window it asks with, the escape hatch, and that "no" comes back as a sentence somebody can act
 * on rather than as a silent false.
 */
class BackupGateTest {

    /** The spec's own defaults, as jcore serves them for a fresh file. */
    private static final SmpSpec DEFAULTS = new SmpSpec() {
    };

    private static final UpdateRequest LAST_NIGHT = new UpdateRequest(7L, UpdateKind.BACKUP,
            UpdateStatus.DONE, UpdateSource.CONSOLE, "steward-worker",
            Instant.parse("2026-09-13T02:45:00Z"), Instant.parse("2026-09-13T02:45:00Z"),
            Instant.parse("2026-09-13T02:45:00Z"), Instant.parse("2026-09-13T02:51:00Z"),
            "{\"stage\":\"DONE\",\"services\":[],\"notes\":[]}");

    @Test
    @DisplayName("a backup inside the window lets the reset through, and is asked for by that window")
    void aRecentBackupIsAYes() {
        final AtomicReference<Duration> asked = new AtomicReference<>();
        final BackupGate gate = new BackupGate(directoryAnswering(asked, Optional.of(LAST_NIGHT)), 12);

        assertTrue(gate.refusal().isEmpty(), "a backup was found and the reset was still refused");
        assertEquals(Duration.ofHours(12), asked.get(),
                "the configured window has to be the one the database is asked with - a gate that"
                        + " asked with its own number would accept a backup the operator had"
                        + " already decided was too old");
    }

    @Test
    @DisplayName("no backup is a refusal that names the window and what to do about it")
    void noBackupIsARefusalSomebodyCanActOn() {
        final BackupGate gate =
                new BackupGate(directoryAnswering(new AtomicReference<>(), Optional.empty()), 12);

        final Optional<String> refusal = gate.refusal();
        assertTrue(refusal.isPresent(),
                "no backup was found and the reset went ahead anyway - the world would be gone");
        final String why = refusal.get();
        // The sentence is the whole product of this class: it is the only thing that happens when
        // a backup is missing, because there is no line from this plugin into the admin channel.
        assertTrue(why.contains("12 hours"), "the refusal does not say how far back it looked: " + why);
        assertTrue(why.contains("NOT RESET"), "the refusal does not say the world is untouched: " + why);
        assertTrue(why.contains("farm-reset-backup-window-hours"),
                "the refusal does not name the setting that governs it: " + why);
    }

    @Test
    @DisplayName("zero switches the check off without touching the database at all")
    void zeroIsTheEscapeHatch() {
        // Not merely "answers empty": the directory below throws on every call, so reaching it is
        // the failure. A stack with no steward-worker has no database worth asking, and a gate
        // that asked anyway would spend the pool's connection timeout on the server thread's
        // behalf every night before deciding it did not care.
        final BackupGate gate = new BackupGate(refusingDirectory(), 0);

        assertTrue(gate.isOff());
        assertTrue(gate.refusal().isEmpty());
    }

    @Test
    @DisplayName("a database that cannot be asked is the caller's problem, not a quiet yes")
    void anUnreachableDatabaseIsNotAYes() {
        // FarmWorldReset catches this and refuses, keeping the throwable for the log. What must
        // never happen is this class swallowing it and answering "go ahead": an unanswerable
        // question is not a backup.
        assertThrows(IllegalStateException.class, () -> new BackupGate(refusingDirectory(), 12).refusal());
    }

    @Test
    @DisplayName("the default window is the one the spec documents")
    void theGateUsesTheConfiguredHours() {
        final AtomicReference<Duration> asked = new AtomicReference<>();
        new BackupGate(directoryAnswering(asked, Optional.of(LAST_NIGHT)),
                DEFAULTS.farmResetBackupWindowHours()).refusal();

        assertEquals(Duration.ofHours(DEFAULTS.farmResetBackupWindowHours()), asked.get());
    }

    /**
     * A directory that answers {@code lastSuccessfulBackup} and refuses everything else.
     *
     * <p>A proxy rather than a mocking library, the way {@code DailyScheduleTest}'s neighbours do
     * it: every other method throws, so a future gate that started reading the table some other
     * way would fail here rather than on a server at five in the morning.</p>
     */
    private static UpdateDirectory directoryAnswering(final AtomicReference<Duration> asked,
                                                      final Optional<UpdateRequest> answer) {
        return (UpdateDirectory) Proxy.newProxyInstance(
                BackupGateTest.class.getClassLoader(),
                new Class<?>[]{UpdateDirectory.class},
                (proxy, method, args) -> {
                    if ("lastSuccessfulBackup".equals(method.getName())) {
                        asked.set((Duration) args[0]);
                        return answer;
                    }
                    if ("toString".equals(method.getName())) {
                        return "one-question directory";
                    }
                    throw new AssertionError("the backup gate called UpdateDirectory#"
                            + method.getName());
                });
    }

    /** A directory that is there and cannot answer - a database that has stopped responding. */
    private static UpdateDirectory refusingDirectory() {
        return (UpdateDirectory) Proxy.newProxyInstance(
                BackupGateTest.class.getClassLoader(),
                new Class<?>[]{UpdateDirectory.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "unreachable directory";
                    }
                    throw new IllegalStateException("the pool timed out");
                });
    }
}
