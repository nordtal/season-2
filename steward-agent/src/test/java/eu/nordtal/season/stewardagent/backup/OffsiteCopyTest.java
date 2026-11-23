package eu.nordtal.season.stewardagent.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.internalapi.agent.Retention;
import eu.nordtal.season.internalapi.agent.SnapshotResult;
import eu.nordtal.season.internalapi.backup.BackupDestinationWire;
import eu.nordtal.season.stewardagent.run.Snapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the copy off the host picks, and that nothing is marked as copied that was not. */
class OffsiteCopyTest {

    private static final Retention POLICY = new Retention(14, 8, 6, 3);

    @TempDir
    Path backups;

    private OffsiteCopy copy(final OffsiteCopy.Destination destination) {
        return new OffsiteCopy(backups, destination, "nordtal-s2", Clock.systemUTC());
    }

    private static BackupDestinationWire.Credentials credentials(final String folderId) {
        return new BackupDestinationWire.Credentials(
                "account-1",
                "admin@example.com",
                folderId,
                "Nordtal Backups",
                "stack-0123456789ab",
                "client-1",
                "secret-1",
                "refresh \"1\"",
                "not the real one");
    }

    /** Names the given credentials, or none, or fails like an unreachable steward, and keeps each report. */
    private static final class Recording implements OffsiteCopy.Destination {

        private final boolean reachable;
        private final BackupDestinationWire.@Nullable Credentials credentials;
        final List<BackupDestinationWire.Report> reports = new ArrayList<>();

        Recording(final boolean reachable, final BackupDestinationWire.@Nullable Credentials credentials) {
            this.reachable = reachable;
            this.credentials = credentials;
        }

        @Override
        public Optional<BackupDestinationWire.Credentials> credentials() throws IOException {
            if (!reachable) {
                throw new IOException("connection refused");
            }
            return Optional.ofNullable(credentials);
        }

        @Override
        public void report(final BackupDestinationWire.Report outcome) {
            reports.add(outcome);
        }
    }

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

        final List<String> picked = copy(OffsiteCopy.Destination.NONE).newestOfEverySeries().stream()
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
    void withoutADestinationThereIsNoCopyAndNoReport() throws IOException {
        touch("nordtal-20261005T024500Z.dump");
        final Recording destination = new Recording(true, null);

        assertEquals(Optional.empty(), copy(destination).copy(POLICY));
        assertEquals(List.of(), destination.reports);
    }

    @Test
    void aStewardThatDoesNotAnswerFailsTheCopyAndHearsNothing() throws IOException {
        touch("nordtal-20261005T024500Z.dump");
        final Recording destination = new Recording(false, null);

        final SnapshotResult result = copy(destination).copy(POLICY).orElseThrow();

        assertFalse(result.ok(), String.valueOf(result));
        assertTrue(String.valueOf(result.message()).contains("steward did not name the destination"), result.message());
        assertEquals(List.of(), destination.reports);
    }

    @Test
    void aCopyThatCouldNotRunFailsMarksNothingAndTellsStewardForWhichAccount() throws IOException {
        touch("nordtal-20261005T024500Z.dump");
        // A folder id that would add a line to the rclone config stops the copy before any tool runs.
        final Recording destination = new Recording(true, credentials("folder\nscope = drive"));

        final SnapshotResult result = copy(destination).copy(POLICY).orElseThrow();

        assertFalse(result.ok(), String.valueOf(result));
        assertEquals(Snapshots.OFFSITE, result.name());
        assertFalse(OffsiteCopy.isCopied(backups, "nordtal-20261005T024500Z.dump"));
        assertFalse(String.valueOf(result.message()).contains("not the real one"), "the password left its env");
        final BackupDestinationWire.Report report = destination.reports.getFirst();
        assertEquals("account-1", report.accountId());
        assertFalse(report.ok());
        assertEquals(result.message(), report.error());
        assertEquals(null, report.copiedAt());
    }

    @Test
    void theRcloneConfigRootsTheRemoteAtTheFolderAndHoldsTheTokenAsJson() {
        final String text = RcloneConfig.text(credentials("folder-1"));

        assertTrue(text.startsWith("[gdrive]\ntype = drive\nscope = drive.file\n"), text);
        assertTrue(text.contains("\nroot_folder_id = folder-1\n"), text);
        assertTrue(text.contains("\nclient_id = client-1\n"), text);
        assertTrue(text.contains("\"refresh_token\":\"refresh \\\"1\\\"\""), text);
        assertFalse(text.contains("not the real one"), "the restic password is no rclone setting");
        assertTrue(text.contains("\nuse_trash = false\n"), text);
    }

    @Test
    void theRcloneTokenHasExpiredSoRcloneRefreshesItBeforeItsFirstCall() {
        final String line = RcloneConfig.text(credentials("folder-1"))
                .lines()
                .filter(each -> each.startsWith("token = "))
                .findFirst()
                .orElseThrow();
        final JsonObject token = Json.decode(line.substring("token = ".length()), JsonObject.class);

        // rclone 1.60 drops the refresh token of a token without an access token.
        assertFalse(token.get("access_token").getAsString().isEmpty(), line);
        assertEquals("refresh \"1\"", token.get("refresh_token").getAsString());
        // A zero expiry never expires, so rclone would send the placeholder to Google as it is.
        final Instant expiry = Instant.parse(token.get("expiry").getAsString());
        assertTrue(expiry.isAfter(Instant.EPOCH) && expiry.isBefore(Instant.now()), line);
    }

    @Test
    void theRcloneConfigIsReadableByThisProcessAloneAndGoneAfterwards() throws IOException {
        final Path file = RcloneConfig.write(credentials("folder-1"));
        final Path directory = file.getParent();
        try {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            Files.writeString(directory.resolve("rclone.conf.renewed"), "token");
        } finally {
            RcloneConfig.delete(file);
        }

        assertFalse(Files.exists(directory), "the directory with the refresh token is left behind");
    }

    @Test
    void theCredentialsNeverPrintTheirSecrets() {
        final String printed = credentials("folder-1").toString();

        assertTrue(printed.contains("admin@example.com"), printed);
        assertFalse(printed.contains("not the real one"), printed);
        assertFalse(printed.contains("refresh"), printed);
        assertFalse(printed.contains("secret-1"), printed);
    }

    @Test
    void theSummaryNamesTheSnapshotAndWhatTheRepositoryGrewBy() {
        final List<String> said = List.of(
                "{\"message_type\":\"status\",\"percent_done\":1}",
                "{\"message_type\":\"summary\",\"data_added\":900,\"data_added_packed\":400,"
                        + "\"snapshot_id\":\"4f1c2d\"}");

        assertEquals(Optional.of(new OffsiteCopy.Summary("4f1c2d", 400)), OffsiteCopy.summary(said));
        assertEquals(
                Optional.of(new OffsiteCopy.Summary("4f1c2d", 900)),
                OffsiteCopy.summary(
                        List.of("{\"message_type\":\"summary\",\"data_added\":900,\"snapshot_id\":\"4f1c2d\"}")));
        assertEquals(Optional.empty(), OffsiteCopy.summary(List.of("{\"message_type\":\"summary\",\"data_added\":0}")));
    }
}
