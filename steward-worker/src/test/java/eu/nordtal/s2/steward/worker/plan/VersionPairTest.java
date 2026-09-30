package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.steward.worker.source.RemoteFile;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The version jump in a report, held to the same table as {@code version-jump.test.ts}.
 *
 * The second half checks that {@link PlanReport} puts the pair into the report.
 */
class VersionPairTest {

    @Test
    void twoBuildsOfOneArtefactComeApartIntoThePartThatDiffers() {
        assertEquals(
                new VersionPair("2.6.18", "2.7.0"),
                VersionPair.of("voicechat-bukkit-2.6.18.jar", "voicechat-bukkit-2.7.0.jar")
                        .orElseThrow());
    }

    @Test
    void theBoundaryIsNeverLeftInsideANumber() {
        // The naive common prefix ends inside `13`, answering `3.0 -> 4.0`: a jump that never happened.
        assertEquals(
                new VersionPair("2.13.0", "2.14.0"),
                VersionPair.of("packetevents-spigot-2.13.0.jar", "packetevents-spigot-2.14.0.jar")
                        .orElseThrow());
    }

    @Test
    void onlyTheHalfThatMovedIsNamed() {
        // 26.2 is on both sides, so it is not the jump; only the half that moved is worth the width to name.
        assertEquals(
                new VersionPair("118", "131"),
                VersionPair.of("paper-26.2-118.jar", "paper-26.2-131.jar").orElseThrow());
    }

    @Test
    void aHashAgainstAFilenameIsNotAVersionJump() {
        // Installed side is a SHA-1, wanted side is a zip name; without the prefix rule both look like version jumps.
        assertEquals(
                Optional.empty(),
                VersionPair.of("c0bac3a03dad681347cbe4a3bc932aff8ffd8203", "nordtal-resource-pack-0.9.4.zip"));
    }

    @Test
    void aRenamedJarKeepsItsFilenameRatherThanBeingGivenAVersion() {
        assertEquals(Optional.empty(), VersionPair.of("CoreProtect.jar", "coreprotect-22.4.jar"));
    }

    @Test
    void nothingOnEitherSideIsNothingToCompare() {
        assertEquals(Optional.empty(), VersionPair.of(null, "proxy-0.9.4.jar"));
        assertEquals(Optional.empty(), VersionPair.of("proxy-0.9.3.jar", null));
        assertEquals(Optional.empty(), VersionPair.of("", "proxy-0.9.4.jar"));
        assertEquals(
                Optional.empty(),
                VersionPair.of("proxy-0.9.4.jar", "proxy-0.9.4.jar"),
                "a jump from a version to itself is not a jump");
    }

    @Test
    void aPairWithNoDigitInItIsAWordNotAVersion() {
        assertEquals(Optional.empty(), VersionPair.of("plugin-alpha.jar", "plugin-beta.jar"));
    }

    @Test
    void theReportCarriesTheJumpNotTheInstalledFilename() {
        final UpdateReport report = PlanReport.of(plan(new Change(
                "smp", "smp", Change.Status.OUTDATED, "smp-0.9.3.jar", file("smp", "0.9.4", "smp-0.9.4.jar"), null)));

        final UpdateReport.Change change =
                report.services().getFirst().changes().getFirst();
        assertEquals("0.9.3", change.from());
        assertEquals("0.9.4", change.to());
        assertTrue(report.render().contains("smp 0.9.3 -> 0.9.4"), report.render());
    }

    @Test
    void thePublishedVersionIsUsedWhenTheTwoNamesDoNotComeApart() {
        // The hash stays: an invented version is worse than an ugly string, and the source published this one.
        final UpdateReport report = PlanReport.of(plan(new Change(
                "proxy",
                "resource-pack",
                Change.Status.OUTDATED,
                "c0bac3a03dad681347cbe4a3bc932aff8ffd8203",
                file("resource-pack", "0.9.4", "nordtal-resource-pack-0.9.4.zip"),
                null)));

        final UpdateReport.Change change =
                report.services().getFirst().changes().getFirst();
        assertEquals("c0bac3a03dad681347cbe4a3bc932aff8ffd8203", change.from());
        assertEquals("0.9.4", change.to());
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(
                Instant.parse("2026-09-20T18:00:00Z"), "v0.9.4", false, List.of(changes), List.of(), List.of());
    }

    private static RemoteFile file(final String artifact, final String version, final String fileName) {
        return new RemoteFile(artifact, version, fileName, URI.create("https://example.invalid/" + fileName), null);
    }
}
