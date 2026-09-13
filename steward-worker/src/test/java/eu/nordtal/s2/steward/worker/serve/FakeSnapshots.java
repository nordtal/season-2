package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.backup.Snapshots;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Saving volumes, without a disk.
 *
 * <p>It shares its call list with {@link FakeArcane} on purpose: what a backup run has to get right
 * is the <b>order</b> of stopping, saving and starting, and an ordering split across two recorders
 * is an ordering nothing asserts.</p>
 */
final class FakeSnapshots implements Snapshots {

    private final List<String> calls;
    private final Set<String> failing = new HashSet<>();
    private final Set<String> empty = new HashSet<>();

    FakeSnapshots(final List<String> calls) {
        this.calls = calls;
    }

    FakeSnapshots() {
        this(new ArrayList<>());
    }

    FakeSnapshots fails(final String volume) {
        failing.add(volume);
        return this;
    }

    /** A volume that "saves" but produces nothing - the A23 shape, as a fixture. */
    FakeSnapshots savesNothing(final String volume) {
        empty.add(volume);
        return this;
    }

    @Override
    public @NotNull SnapshotResult save(final @NotNull String volume) {
        calls.add("backup:" + volume);
        if (failing.contains(volume)) {
            return SnapshotResult.failed(volume, Duration.ofSeconds(1),
                    "tar exited 2: " + volume + " is not a readable archive");
        }
        if (empty.contains(volume)) {
            return SnapshotResult.failed(volume, Duration.ofSeconds(1),
                    "nothing was saved: " + volume + " is empty or not mounted");
        }
        return SnapshotResult.saved(volume, 1_234_567, Duration.ofSeconds(12),
                "/backups/" + volume + "-20260913T000000Z.tar.zst");
    }

    @Override
    public @NotNull List<String> prune(final int keep) {
        calls.add("prune:" + keep);
        return List.of();
    }
}
