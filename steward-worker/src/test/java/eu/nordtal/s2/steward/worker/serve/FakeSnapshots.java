package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.steward.worker.backup.SnapshotResult;
import eu.nordtal.s2.steward.worker.backup.Snapshots;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Saving volumes, without a disk.
 *
 * It shares its call list with {@link FakeContainers} on purpose: what a backup run has to get right is the order of
 * stopping, saving and starting, and an ordering split across two recorders is an ordering nothing asserts.
 */
final class FakeSnapshots implements Snapshots {

    private final List<String> calls;
    private final Set<String> failing = new HashSet<>();
    private final Set<String> empty = new HashSet<>();
    private final Map<String, String> marks = new LinkedHashMap<>();

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
    public SnapshotResult save(final String volume) {
        calls.add("backup:" + volume);
        if (failing.contains(volume)) {
            return SnapshotResult.failed(
                    volume, Duration.ofSeconds(1), "tar exited 2: " + volume + " is not a readable archive");
        }
        if (empty.contains(volume)) {
            return SnapshotResult.failed(
                    volume, Duration.ofSeconds(1), "nothing was saved: " + volume + " is empty or not mounted");
        }
        return SnapshotResult.saved(
                volume, 1_234_567, Duration.ofSeconds(12), "/backups/" + volume + "-20260913T000000Z.tar.zst");
    }

    /** The mark, recorded in the same call list, because when it is written is half the point. */
    @Override
    public @Nullable String markUnverified(final String archive, final String why) {
        calls.add("mark:" + archive.substring(archive.lastIndexOf('/') + 1));
        marks.put(archive, why);
        return archive.substring(archive.lastIndexOf('/') + 1) + ".unverified";
    }

    /**
     * What was marked, and with which sentence, in the order the marks were made.
     *
     * Copies the {@link LinkedHashMap} field directly, never through {@code Map.copyOf}: that call randomises
     * iteration order per JVM, which would make an order assertion pass intermittently. Whether order is a claim
     * worth asserting is decided against the shared call list, which is where ordering lives in these doubles.
     */
    Map<String, String> marks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(marks));
    }

    @Override
    public List<String> prune(final eu.nordtal.s2.steward.worker.backup.Retention policy) {
        calls.add("prune:" + policy.daily());
        return List.of();
    }
}
