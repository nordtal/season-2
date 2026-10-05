package eu.nordtal.season.stewardagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The one-shot's log outlives its container: everything it writes is in the file the moment it is written. */
class RunLogTest {

    @TempDir
    Path backups;

    /** Read while the run still holds the file, as a kill would leave it. */
    @Test
    void whatTheOneShotWritesIsInTheFileBeforeItEnds() throws IOException {
        final PrintStream err = System.err;
        final Path file = backups.resolve(RunLog.FOLDER).resolve("42.log");
        try (RunLog ignored = RunLog.keep(backups, 42)) {
            System.err.println("the plan holds smp");
            System.out.println("request 42");
            LoggerFactory.getLogger(RunLogTest.class).info("migrate exited with 0");

            final String kept = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(kept.contains("the plan holds smp"), kept);
            assertTrue(kept.contains("request 42"), kept);
            assertTrue(kept.contains("migrate exited with 0"), kept);
        }
        assertSame(err, System.err, "closing puts the stream back");
    }

    @Test
    void onlyTheNewestTenRunLogsStayAndNothingElseIsTouched() throws IOException {
        final Path folder = Files.createDirectories(backups.resolve(RunLog.FOLDER));
        for (int id = 1; id <= 12; id++) {
            Files.writeString(folder.resolve(id + ".log"), "run " + id);
        }
        Files.writeString(folder.resolve("notes.txt"), "kept by hand");

        try (RunLog ignored = RunLog.keep(backups, 13)) {
            // Opening is what prunes.
        }

        try (Stream<Path> left = Files.list(folder)) {
            assertEquals(
                    List.of(
                            "10.log",
                            "11.log",
                            "12.log",
                            "13.log",
                            "4.log",
                            "5.log",
                            "6.log",
                            "7.log",
                            "8.log",
                            "9.log",
                            "notes.txt"),
                    left.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void aBackupsFolderThatCannotBeWrittenCostsTheCopyAndNotTheRun() throws IOException {
        final Path blocked = Files.writeString(backups.resolve("not-a-folder"), "a file");

        try (RunLog ignored = RunLog.keep(blocked, 7)) {
            System.err.println("the run goes on");
        }

        assertTrue(Files.isRegularFile(blocked));
    }
}
