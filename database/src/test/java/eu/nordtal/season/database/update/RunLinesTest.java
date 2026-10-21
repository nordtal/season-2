package eu.nordtal.season.database.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.DatabaseText;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the lines a step's facts read as, the same for Steward and the terminal. */
class RunLinesTest {

    private static List<String> english(final RunStep.Row step) {
        return RunLines.of(step).stream().map(DatabaseText::english).toList();
    }

    private static RunStep.Row step(
            final RunStep.Kind kind,
            final RunStep.@org.jspecify.annotations.Nullable Reason reason,
            final @org.jspecify.annotations.Nullable Integer exitCode) {
        return new RunStep.Row(
                1,
                kind,
                null,
                null,
                null,
                RunStep.Outcome.DONE,
                reason,
                exitCode,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    @Test
    void aStepWithoutFactsHasNoLines() {
        assertEquals(List.of(), english(step(RunStep.Kind.STOP, null, null)));
    }

    @Test
    void aFailedToolNamesItsReasonAndExitCode() {
        assertEquals(
                List.of("the tool failed", "exit code 2"),
                english(step(RunStep.Kind.DUMP_DATABASE, RunStep.Reason.TOOL_FAILED, 2)));
    }

    @Test
    void aPackShowsTheFirstEightCharactersOfItsFingerprint() {
        final RunStep.Row row = new RunStep.Row(
                1,
                RunStep.Kind.INSTALL,
                "proxy",
                null,
                null,
                RunStep.Outcome.DONE,
                null,
                null,
                List.of(new RunStep.Change("resource-pack", "c0bac3a03dad681347cbe4a3bc932aff8ffd8203", "0.9.4", null)),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
        assertEquals(List.of("resource-pack c0bac3a0 → 0.9.4"), english(row));
    }

    @Test
    void anArtefactWithAVersionComesBeforeOneWithout() {
        final List<RunStep.Change> image = List.of(
                new RunStep.Change("hunger-games", "0.51.0", "0.52.0", null),
                new RunStep.Change("image", null, null, null));
        final List<RunStep.Change> proxy = List.of(
                new RunStep.Change("image", null, null, null), new RunStep.Change("proxy", "0.51.0", "0.52.0", null));
        assertEquals(List.of("hunger-games 0.51.0 → 0.52.0", "image no build yet"), english(resolve(image)));
        assertEquals(List.of("proxy 0.51.0 → 0.52.0", "image no build yet"), english(resolve(proxy)));
        assertEquals(List.of("proxy 0.51.0 → 0.52.0", "image no build yet"), english(resolve(proxy.reversed())));
    }

    private static RunStep.Row resolve(final List<RunStep.Change> changes) {
        return new RunStep.Row(
                1,
                RunStep.Kind.RESOLVE,
                "proxy",
                null,
                null,
                RunStep.Outcome.DONE,
                null,
                null,
                changes,
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of());
    }

    @Test
    void everyFactIsALine() {
        final RunStep.Row row = new RunStep.Row(
                1,
                RunStep.Kind.INSTALL,
                "smp",
                null,
                null,
                RunStep.Outcome.DONE,
                null,
                null,
                List.of(
                        new RunStep.Change("paper", "26.2.121", "26.2.126", RunStep.Image.PULLED),
                        new RunStep.Change("smp", null, "0.50.0", null)),
                List.of(new RunStep.Move("smp", "limbo", 3, 2)),
                List.of(new RunStep.ImageRemoved("a", 1024), new RunStep.ImageRemoved("b", 1024)),
                2048L,
                new RunStep.Room(4096, 1024 * 1024),
                new RunStep.Offsite("4f1c2d", 512),
                List.of(new RunStep.Archive("database", "nordtal-1.dump", 400, 1000, true, null)),
                List.of(new RunStep.Archive(
                        "mc-smp", "nordtal-s2_mc-smp-1.tar.gz", 900, 40_000, false, RunStep.PruneRule.OVER_BUDGET)));
        assertEquals(
                List.of(
                        "paper 26.2.121 → 26.2.126",
                        "from a pulled image",
                        "smp 0.50.0",
                        "3 players moved from smp to limbo, 2 back",
                        "2 images removed, 2 KiB freed",
                        "build cache 2 KiB freed",
                        "4 KiB needed, 1 MiB free",
                        "snapshot 4f1c2d, 512 B added",
                        "nordtal-1.dump, 400 B in 1s",
                        "nordtal-s2_mc-smp-1.tar.gz, over the disk budget"),
                english(row));
    }
}
