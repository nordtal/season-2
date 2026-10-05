package eu.nordtal.season.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.stewardagent.run.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the copy off the host picks, and that nothing is marked as copied that was not. */
class OffsiteCopyTest {

    private static final Retention POLICY = new Retention(14, 8, 6, 3);

    @TempDir
    Path backups;

    private void touch(final String... names) throws IOException {
        for (final String name : names) {
            Files.writeString(backups.resolve(name), name);
        }
    }

    @Test
    void theNewestFinishedArchiveOfEverySeriesIsPickedAndNothingElse() throws IOException {
        touch(
                "nordtal-20261004T024500Z.dump",
                "nordtal-20261005T024500Z.dump",
                "nordtal-s2_mc-smp-20261004T024600Z.tar.zst",
                "nordtal-s2_mc-smp-20261005T024600Z.tar.zst",
                "nordtal-s2_mc-smp-20261005T030000Z.tar.zst.partial",
                "nordtal-s2_mc-smp-20261005T024600Z.tar.zst.unverified",
                "nordtal-s2_bot-config-20261003T024600Z.tar.zst");
        Files.createDirectories(backups.resolve("runs"));

        final List<String> picked = new OffsiteCopy(backups, Optional::empty)
                .newestOfEverySeries().stream()
                        .map(path -> path.getFileName().toString())
                        .toList();

        assertEquals(
                List.of(
                        "nordtal-20261005T024500Z.dump",
                        "nordtal-s2_bot-config-20261003T024600Z.tar.zst",
                        "nordtal-s2_mc-smp-20261005T024600Z.tar.zst"),
                picked);
    }

    @Test
    void withoutARepositoryThereIsNoCopyAndNoFailure() throws IOException {
        touch("nordtal-20261005T024500Z.dump");

        assertEquals(Optional.empty(), new OffsiteCopy(backups, Optional::empty).copy(POLICY));
    }

    @Test
    void aCopyThatCouldNotRunFailsAndMarksNothing() throws IOException {
        touch("nordtal-20261005T024500Z.dump");
        final OffsiteCopy.Target target = new OffsiteCopy.Target(
                "sftp://u1@box.invalid:23/nordtal-s2",
                "not the real one",
                backups.resolve("missing-key"),
                backups.resolve("missing-hosts"),
                "nordtal-s2");

        final SnapshotResult result =
                new OffsiteCopy(backups, () -> Optional.of(target)).copy(POLICY).orElseThrow();

        assertFalse(result.ok(), String.valueOf(result));
        assertEquals(Snapshots.OFFSITE, result.name());
        assertFalse(OffsiteCopy.isCopied(backups, "nordtal-20261005T024500Z.dump"));
        assertFalse(String.valueOf(result.message()).contains("not the real one"), "the password left its env");
    }

    @Test
    void theTargetNeverPrintsItsPassword() {
        final OffsiteCopy.Target target =
                new OffsiteCopy.Target("sftp://box/x", "hunter2", Path.of("k"), Path.of("h"), "nordtal-s2");

        assertTrue(target.toString().contains("sftp://box/x"));
        assertFalse(target.toString().contains("hunter2"));
    }
}
