package eu.nordtal.s2.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Checks that a report survives the trip through {@code update_request.result} and back.
 *
 * A {@code null} version must not come back as {@code "null"}, typed text may hold quotes and newlines,
 * and plain-text rows must stay drawable.
 */
class UpdateReportsTest {

    @Test
    void aWholeReportRoundTrips() {
        final UpdateReport report = new UpdateReport(
                UpdateReport.Stage.VERIFYING,
                List.of(
                        new UpdateReport.ServiceLine(
                                "smp",
                                UpdateReport.State.HEALTHY,
                                List.of(
                                        new UpdateReport.Change("paper", "26.2.121", "26.2.126"),
                                        new UpdateReport.Change("smp", "0.6.0", "0.7.0")),
                                null),
                        new UpdateReport.ServiceLine(
                                "limbo",
                                UpdateReport.State.FAILED,
                                List.of(new UpdateReport.Change("limbo", null, "0.7.0")),
                                "did not report healthy within 5 minutes")),
                List.of("the schema is current", "the pack was written"));

        final Optional<UpdateReport> back = UpdateReports.parse(UpdateReports.toJson(report));

        assertTrue(back.isPresent());
        assertEquals(report, back.get(), "every field, in order, including the nulls");
    }

    @Test
    void aVersionThatIsNotInstalledYetStaysAbsent() {
        final UpdateReport report = new UpdateReport(
                UpdateReport.Stage.PLANNED,
                List.of(new UpdateReport.ServiceLine(
                        "steward-worker",
                        UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("chunky", null, "1.4.36")),
                        null)),
                List.of());

        final UpdateReport back =
                UpdateReports.parse(UpdateReports.toJson(report)).orElseThrow();

        assertEquals(
                null,
                back.services().get(0).changes().get(0).from(),
                "a first deployment installs rather than upgrades, and the report has to be able"
                        + " to say so - 'null -> 1.4.36' in an embed is a bug report waiting to"
                        + " happen");
        assertTrue(back.render().contains("chunky 1.4.36"));
        assertFalse(back.render().contains("null"));
    }

    @Test
    void aFailureMessageCarryingQuotesAndNewlinesComesBackAsItWentIn() {
        final String nasty = "the daemon answered 404 for \"/containers/abc123/stop\".\nIts id\tis"
                + " twelve hex characters, not a service name.";
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED).withNote(nasty);

        assertEquals(
                nasty,
                UpdateReports.parse(UpdateReports.toJson(report))
                        .orElseThrow()
                        .notes()
                        .get(0));
    }

    @Test
    void anEmptyReportRoundTripsArraysAndAll() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.RESOLVING);

        final UpdateReport back =
                UpdateReports.parse(UpdateReports.toJson(report)).orElseThrow();

        assertEquals(UpdateReport.Stage.RESOLVING, back.stage());
        assertTrue(back.services().isEmpty());
        assertTrue(back.notes().isEmpty());
    }

    @Test
    void thePlainTextAnOlderWorkerWroteIsNotAReportAndDoesNotThrow() {
        // Plain-text rows must parse to empty without throwing.
        assertEquals(Optional.empty(), UpdateReports.parse("Nothing needed doing.\n  smp: paper 26.2.121 (current)"));
        assertEquals(Optional.empty(), UpdateReports.parse(null));
        assertEquals(Optional.empty(), UpdateReports.parse(""));
        assertEquals(Optional.empty(), UpdateReports.parse("{\"stage\":\"NOT_A_STAGE\"}"));
        assertEquals(Optional.empty(), UpdateReports.parse("{\"stage\":"));
    }

    @Test
    void aServiceLineIsReplacedAsTheRunWalksPastItNotAppended() {
        final UpdateReport.Change change = new UpdateReport.Change("smp", "0.6.0", "0.7.0");
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED, List.of(change), null));

        report = report.with(report.line("smp").at(UpdateReport.State.STOPPED));
        report = report.with(report.line("smp").at(UpdateReport.State.HEALTHY));

        assertEquals(
                1,
                report.services().size(),
                "a run walks the same services four times; appending would give Discord four"
                        + " fields for one server");
        assertEquals(UpdateReport.State.HEALTHY, report.services().get(0).state());
        assertEquals(
                List.of(change),
                report.services().get(0).changes(),
                "and moving a service's state must not lose what it is changing to");
    }

    @Test
    void aServiceNobodyHasMentionedReadsAsUnchangedRatherThanAsMissing() {
        assertEquals(
                UpdateReport.State.UNCHANGED,
                UpdateReport.at(UpdateReport.Stage.PLANNED).line("limbo").state());
    }

    @Test
    void newsAndWorkAreDifferentAnswers() {
        assertFalse(
                UpdateReport.at(UpdateReport.Stage.PLANNED)
                        .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.UNCHANGED, List.of(), null))
                        .isWork(),
                "a service with no changes is not work, and a run of them is news");

        assertTrue(UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp", "0.6.0", "0.7.0")),
                        null))
                .isWork());
    }

    @Test
    void theTextRenderingIsTheOneTextRendering() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.HEALTHY,
                        List.of(new UpdateReport.Change("paper", "26.2.121", "26.2.126")),
                        null))
                .withNote("the schema is current");

        assertEquals("""
                Update finished
                smp: running - paper 26.2.121 -> 26.2.126
                the schema is current""", report.render());
    }

    @Test
    void anArtefactWithNoBuildForThisVersionIsNewsAndNeverWork() {
        // A line with only unsupported changes must not stop, install or close as installed.
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")),
                        null));

        assertFalse(report.isWork(), report.render());
        assertFalse(report.line("smp").isMoving(), report.render());
        assertTrue(
                report.render().contains("coreprotect"),
                "an artefact waiting for a build has to stay NAMED: " + report.render());
    }

    @Test
    void oneArtefactMovingBesideOneThatCannotMakesTheServiceWorkAgain() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.PLANNED,
                        List.of(
                                UpdateReport.Change.unsupported("coreprotect"),
                                new UpdateReport.Change("smp", "0.6.0", "0.7.0")),
                        null));

        assertTrue(report.isWork(), report.render());
        assertTrue(report.line("smp").isMoving(), report.render());
    }

    @Test
    void anUnsupportedArtefactSurvivesTheColumnAndAnOrdinaryOneWritesNoState() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.PLANNED,
                        List.of(
                                UpdateReport.Change.unsupported("coreprotect"),
                                new UpdateReport.Change("smp", "0.6.0", "0.7.0")),
                        null));

        final String json = UpdateReports.toJson(report);
        assertEquals(report, UpdateReports.parse(json).orElseThrow());

        // Written only for the non-default state, so older readers still parse ordinary reports.
        assertEquals(1, json.split("\"state\":\"UNSUPPORTED\"", -1).length - 1, json);
        assertFalse(json.contains("\"state\":\"MOVING\""), json);
    }

    @Test
    void aFailureSaysWhichServiceAndWhyInTheTextToo() {
        final String rendered = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(new UpdateReport.ServiceLine("limbo", UpdateReport.State.PLANNED, List.of(), null)
                        .failed("did not report healthy within 5 minutes"))
                .render();

        assertTrue(rendered.contains("limbo: FAILED"), rendered);
        assertTrue(rendered.contains("did not report healthy within 5 minutes"), rendered);
    }
}
