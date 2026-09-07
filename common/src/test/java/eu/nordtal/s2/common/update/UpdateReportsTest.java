package eu.nordtal.s2.common.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report survives the trip through {@code update_request.result} and back.
 *
 * <h2>What is actually at risk</h2>
 * The writer and the reader are in different containers, and between them sits a {@code text}
 * column. Three things in that trip are worth pinning rather than assuming: a {@code null} version
 * means "nothing is installed yet" and must not come back as the four letters {@code null}; the
 * text a person typed into a config or a failure message can contain a quote or a newline; and the
 * rows already in a deployed database are <em>plain text</em> from before 2026-09-07, which every
 * surface still has to be able to draw.
 */
class UpdateReportsTest {

    @Test
    @DisplayName("a whole report round-trips")
    void theReportSurvivesTheColumn() {
        final UpdateReport report = new UpdateReport(UpdateReport.Stage.VERIFYING, List.of(
                new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY, List.of(
                        new UpdateReport.Change("paper", "26.2.121", "26.2.126"),
                        new UpdateReport.Change("smp", "0.6.0", "0.7.0")), null),
                new UpdateReport.ServiceLine("limbo", UpdateReport.State.FAILED, List.of(
                        new UpdateReport.Change("limbo", null, "0.7.0")),
                        "did not report healthy within 5 minutes")),
                List.of("the schema is current", "the pack was written"));

        final Optional<UpdateReport> back = UpdateReports.parse(UpdateReports.toJson(report));

        assertTrue(back.isPresent());
        assertEquals(report, back.get(), "every field, in order, including the nulls");
    }

    @Test
    @DisplayName("a version that is not installed yet stays absent")
    void aNullVersionIsNotTheWordNull() {
        final UpdateReport report = new UpdateReport(UpdateReport.Stage.PLANNED, List.of(
                new UpdateReport.ServiceLine("updater", UpdateReport.State.PLANNED, List.of(
                        new UpdateReport.Change("chunky", null, "1.4.36")), null)),
                List.of());

        final UpdateReport back = UpdateReports.parse(UpdateReports.toJson(report)).orElseThrow();

        assertEquals(null, back.services().get(0).changes().get(0).from(),
                "a first deployment installs rather than upgrades, and the report has to be able"
                        + " to say so - 'null -> 1.4.36' in an embed is a bug report waiting to"
                        + " happen");
        assertTrue(back.render().contains("chunky 1.4.36"));
        assertFalse(back.render().contains("null"));
    }

    @Test
    @DisplayName("a failure message carrying quotes and newlines comes back as it went in")
    void textIsEscaped() {
        final String nasty = "Arcane answered 404 for \"/api/…/redeploy\".\nBoth path segments\tare"
                + " ids, not names.";
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED).withNote(nasty);

        assertEquals(nasty, UpdateReports.parse(UpdateReports.toJson(report))
                .orElseThrow().notes().get(0));
    }

    @Test
    @DisplayName("an empty report round-trips, arrays and all")
    void anEmptyReportIsStillAReport() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.RESOLVING);

        final UpdateReport back = UpdateReports.parse(UpdateReports.toJson(report)).orElseThrow();

        assertEquals(UpdateReport.Stage.RESOLVING, back.stage());
        assertTrue(back.services().isEmpty());
        assertTrue(back.notes().isEmpty());
    }

    @Test
    @DisplayName("the plain text an older updater wrote is not a report, and does not throw")
    void oldRowsAreNotReports() {
        // A deployed database holds these today. Every surface falls back to printing the text, so
        // the one thing this must never do is throw on the way to that fallback.
        assertEquals(Optional.empty(), UpdateReports.parse(
                "Nothing needed doing.\n  smp: paper 26.2.121 (current)"));
        assertEquals(Optional.empty(), UpdateReports.parse(null));
        assertEquals(Optional.empty(), UpdateReports.parse(""));
        assertEquals(Optional.empty(), UpdateReports.parse("{\"stage\":\"NOT_A_STAGE\"}"));
        assertEquals(Optional.empty(), UpdateReports.parse("{\"stage\":"));
    }

    // ---------------------------------------------------------------- building one up

    @Test
    @DisplayName("a service line is replaced as the run walks past it, not appended")
    void aServiceHasOneLineThroughoutTheRun() {
        final UpdateReport.Change change = new UpdateReport.Change("smp", "0.6.0", "0.7.0");
        UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED,
                        List.of(change), null));

        report = report.with(report.line("smp").at(UpdateReport.State.STOPPED));
        report = report.with(report.line("smp").at(UpdateReport.State.HEALTHY));

        assertEquals(1, report.services().size(),
                "a run walks the same services four times; appending would give Discord four"
                        + " fields for one server");
        assertEquals(UpdateReport.State.HEALTHY, report.services().get(0).state());
        assertEquals(List.of(change), report.services().get(0).changes(),
                "and moving a service's state must not lose what it is changing to");
    }

    @Test
    @DisplayName("a service nobody has mentioned reads as unchanged rather than as missing")
    void anUnknownServiceIsUnchanged() {
        assertEquals(UpdateReport.State.UNCHANGED,
                UpdateReport.at(UpdateReport.Stage.PLANNED).line("limbo").state());
    }

    @Test
    @DisplayName("news and work are different answers")
    void nothingToDoIsNotWork() {
        assertFalse(UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.UNCHANGED,
                        List.of(), null))
                .isWork(), "a service with no changes is not work, and a run of them is news");

        assertTrue(UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED,
                        List.of(new UpdateReport.Change("smp", "0.6.0", "0.7.0")), null))
                .isWork());
    }

    @Test
    @DisplayName("the text rendering is the one text rendering")
    void theTextFormIsGeneratedFromTheSameObject() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.HEALTHY,
                        List.of(new UpdateReport.Change("paper", "26.2.121", "26.2.126")), null))
                .withNote("the schema is current");

        assertEquals("""
                Update finished
                smp: running - paper 26.2.121 -> 26.2.126
                the schema is current""", report.render());
    }

    @Test
    @DisplayName("a failure says which service and why, in the text too")
    void aFailedServiceCarriesItsReason() {
        final String rendered = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(new UpdateReport.ServiceLine("limbo", UpdateReport.State.PLANNED, List.of(),
                        null).failed("did not report healthy within 5 minutes"))
                .render();

        assertTrue(rendered.contains("limbo: FAILED"), rendered);
        assertTrue(rendered.contains("did not report healthy within 5 minutes"), rendered);
    }
}
