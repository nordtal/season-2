package eu.nordtal.s2.steward.worker.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.steward.worker.source.RemoteFile;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version jump in a report (season-2-ops/142).
 *
 * <p>The table below is the same table {@code version-jump.test.ts} holds for the Available card,
 * case for case, because the two implementations are one rule - that is what makes them copies
 * rather than two opinions. The second half of this file is the part only this side has: that
 * {@link PlanReport} actually puts the pair into the report, so that Discord, the chat follower and
 * the run's own page all stop printing a filename against a version.</p>
 */
class VersionPairTest {

    // ---------------------------------------------------------------- the rule

    @Test
    @DisplayName("two builds of one artefact come apart into the part that differs")
    void theVersionIsWhatDiffers() {
        assertEquals(
                new VersionPair("2.6.18", "2.7.0"),
                VersionPair.of("voicechat-bukkit-2.6.18.jar", "voicechat-bukkit-2.7.0.jar")
                        .orElseThrow());
    }

    @Test
    @DisplayName("the boundary is never left inside a number")
    void itDoesNotStopInTheMiddleOfANumber() {
        // The naive common prefix ends inside `13` and would answer `3.0 -> 4.0`: a jump that never
        // happened, printed with total confidence. PacketEvents is the artefact that proves it.
        assertEquals(
                new VersionPair("2.13.0", "2.14.0"),
                VersionPair.of("packetevents-spigot-2.13.0.jar", "packetevents-spigot-2.14.0.jar")
                        .orElseThrow());
    }

    @Test
    @DisplayName("only the half that moved is named")
    void itNamesWhatChanged() {
        // 26.2 is on both sides, so it is not the jump - and spending the width on the half that is
        // the same in order to say the half that is not is exactly what this ticket was about.
        assertEquals(
                new VersionPair("118", "131"),
                VersionPair.of("paper-26.2-118.jar", "paper-26.2-131.jar").orElseThrow());
    }

    @Test
    @DisplayName("a hash against a filename is not a version jump")
    void theResourcePackIsRefused() {
        // THE CASE THAT MADE THE PREFIX RULE. The pack's installed side is its SHA-1 and its wanted
        // side is a zip's name. Without the rule these two come apart into "the whole hash" and
        // "the whole filename", both of which contain a digit, and the report prints the pair as if
        // it were a version jump.
        assertEquals(
                Optional.empty(),
                VersionPair.of("c0bac3a03dad681347cbe4a3bc932aff8ffd8203", "nordtal-resource-pack-0.9.4.zip"));
    }

    @Test
    @DisplayName("a renamed jar keeps its filename rather than being given a version")
    void aRenamedJarIsRefused() {
        assertEquals(Optional.empty(), VersionPair.of("CoreProtect.jar", "coreprotect-22.4.jar"));
    }

    @Test
    @DisplayName("nothing on either side is nothing to compare")
    void oneSidedComparisonsAreRefused() {
        assertEquals(Optional.empty(), VersionPair.of(null, "proxy-0.9.4.jar"));
        assertEquals(Optional.empty(), VersionPair.of("proxy-0.9.3.jar", null));
        assertEquals(Optional.empty(), VersionPair.of("", "proxy-0.9.4.jar"));
        assertEquals(
                Optional.empty(),
                VersionPair.of("proxy-0.9.4.jar", "proxy-0.9.4.jar"),
                "a jump from a version to itself is not a jump");
    }

    @Test
    @DisplayName("a pair with no digit in it is a word, not a version")
    void aPairWithoutDigitsIsRefused() {
        assertEquals(Optional.empty(), VersionPair.of("plugin-alpha.jar", "plugin-beta.jar"));
    }

    // ---------------------------------------------------------------- and it reaches the report

    @Test
    @DisplayName("the report carries the jump, not the installed filename")
    void thePlanReportPrintsTheJump() {
        final UpdateReport report = PlanReport.of(plan(new Change(
                "smp", "smp", Change.Status.OUTDATED, "smp-0.9.3.jar", file("smp", "0.9.4", "smp-0.9.4.jar"), null)));

        final UpdateReport.Change change =
                report.services().getFirst().changes().getFirst();
        assertEquals("0.9.3", change.from());
        assertEquals("0.9.4", change.to());
        assertTrue(report.render().contains("smp 0.9.3 -> 0.9.4"), report.render());
    }

    @Test
    @DisplayName("the published version is used when the two names do not come apart")
    void theFallbackIsTheFilenameAndTheVersion() {
        // The pack again, this time through the report: the hash stays, because an invented version
        // would be worse than an ugly string, and the version is what the source published.
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

    // ---------------------------------------------------------------- helpers

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(
                Instant.parse("2026-09-20T18:00:00Z"), "v0.9.4", false, List.of(changes), List.of(), List.of());
    }

    private static RemoteFile file(final String artifact, final String version, final String fileName) {
        return new RemoteFile(artifact, version, fileName, URI.create("https://example.invalid/" + fileName), null);
    }
}
