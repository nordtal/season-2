package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Checks that a report survives the trip through the run's outcome and back.
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
                                TEXTS.report().words("did not report healthy within 5 minutes"))),
                List.of(
                        UpdateReport.Note.done(
                                UpdateReport.Step.MIGRATE, TEXTS.report().words("the schema is current")),
                        UpdateReport.Note.warning(
                                        UpdateReport.Step.STANDBY,
                                        TEXTS.report().standbyStopped())
                                .on("limbo-standby")));

        final Optional<UpdateReport> back = UpdateReports.parse(UpdateReports.toJson(report));

        assertTrue(back.isPresent());
        assertEquals(report, back.get(), "every field, in order, including the nulls");
    }

    @Test
    void aVersionThatIsNotInstalledYetStaysAbsent() {
        final UpdateReport report = new UpdateReport(
                UpdateReport.Stage.PLANNED,
                List.of(new UpdateReport.ServiceLine(
                        "steward",
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
        assertEquals("1.4.36", back.services().get(0).changes().get(0).to());
    }

    @Test
    void aFailureMessageCarryingQuotesAndNewlinesComesBackAsItWentIn() {
        final String nasty = "the daemon answered 404 for \"/containers/abc123/stop\".\nIts id\tis"
                + " twelve hex characters, not a service name.";
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .withNote(UpdateReport.Note.failed(
                                UpdateReport.Step.STOP, TEXTS.report().words(nasty))
                        .on("smp"));

        assertEquals(
                UpdateReport.Note.failed(UpdateReport.Step.STOP, TEXTS.report().words(nasty))
                        .on("smp"),
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
    void thePlainTextAnOlderStewardWroteIsNotAReportAndDoesNotThrow() {
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
    void anArtefactWithNoBuildForThisVersionIsNewsAndNeverWork() {
        // A line with only unsupported changes must not stop, install or close as installed.
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.UNCHANGED,
                        List.of(UpdateReport.Change.unsupported("coreprotect")),
                        null));

        assertFalse(report.isWork(), report.toString());
        assertFalse(report.line("smp").isMoving(), report.toString());
        assertTrue(
                report.line("smp").changes().stream()
                        .anyMatch(change -> change.artefact().equals("coreprotect")),
                "an artefact waiting for a build has to stay NAMED: " + report);
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

        assertTrue(report.isWork(), report.toString());
        assertTrue(report.line("smp").isMoving(), report.toString());
    }

    @Test
    void aChangeWrittenWithoutItsStateReadsAsMoving() {
        final UpdateReport read = UpdateReports.parse(
                        "{\"stage\":\"PLANNED\",\"services\":[{\"service\":\"smp\",\"state\":\"PLANNED\","
                                + "\"changes\":[{\"artefact\":\"smp\",\"to\":\"0.7.0\"}]}],\"notes\":[]}")
                .orElseThrow();

        assertEquals(
                UpdateReport.Change.State.MOVING,
                read.services().getFirst().changes().getFirst().state());
    }

    @Test
    void anUnsupportedArtefactSurvivesTheColumn() {
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
        assertEquals(1, json.split("\"state\":\"UNSUPPORTED\"", -1).length - 1, json);
    }

    @Test
    void aChangeToldInWordsKeepsItsMessageAndTheTerminalReadsItInEnglish() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.PLANNED)
                .with(new UpdateReport.ServiceLine(
                        "smp",
                        UpdateReport.State.PLANNED,
                        List.of(UpdateReport.Change.told("image", TEXTS.report().imageOutdated())),
                        null));

        final String json = UpdateReports.toJson(report);
        assertEquals(report, UpdateReports.parse(json).orElseThrow());
        assertTrue(report.line("smp").isMoving(), "a told change still stops its service");
        assertTrue(json.contains("\"key\":\"report.image-outdated\""), json);
        assertFalse(json.contains("out of date"), json);
        final String english = UpdateReports.english(json);
        assertTrue(english.contains("\"told\":\"out of date\""), english);
    }

    @Test
    void aChangeStoredInWordsByAnEarlierReleaseStillReadsAsItWas() {
        // An image change as the inbox keeps it from before it was a message.
        final String stored = "{\"stage\": \"DONE\", \"notes\": [], \"services\": [{\"state\": \"HEALTHY\","
                + " \"changes\": [{\"to\": \"out of date\", \"state\": \"MOVING\", \"artefact\": \"image\"}],"
                + " \"service\": \"smp\"}]}";

        final UpdateReport.Change change =
                UpdateReports.parse(stored).orElseThrow().line("smp").changes().getFirst();

        assertEquals("out of date", change.to());
        assertEquals(null, change.told());
        assertTrue(UpdateReports.english(stored).contains("\"to\":\"out of date\""), UpdateReports.english(stored));
    }

    @Test
    void aSavedBackupCarriesItsSizeAndItsTimeAsValuesAndNeverAsWords() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.DONE)
                .with(new UpdateReport.ServiceLine(
                        "nordtal-s2_mc-smp",
                        UpdateReport.State.SAVED,
                        List.of(UpdateReport.Change.told(
                                "backup",
                                TEXTS.report().saved(ByteSize.of(1_234_567).message(), Duration.ofSeconds(72)))),
                        null));

        final String json = UpdateReports.toJson(report);
        // A unit comes back as its word, which a select chooses on as it did on the constant.
        assertEquals(
                UpdateReports.english(json),
                UpdateReports.english(
                        UpdateReports.toJson(UpdateReports.parse(json).orElseThrow())));
        assertTrue(json.contains("\"key\":\"report.size\""), json);
        assertTrue(json.contains("\"kind\":\"duration\""), json);
        assertFalse(json.contains("MiB"), "the unit is a choice the bundle words: " + json);
        final String english = UpdateReports.english(json);
        assertTrue(english.contains("\"told\":\"saved 1.2 MiB in 1m 12s\""), english);
    }

    @Test
    void whatWasSeenOfAServiceIsAMessageAndARowThatSaidItInWordsStillReads() {
        final String told = UpdateReports.english(UpdateReports.toJson(UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(new UpdateReport.ServiceLine("smp", UpdateReport.State.PLANNED, List.of(), null)
                        .failed(TEXTS.report().notHealthy(5, TEXTS.report().noContainer())))));
        assertTrue(told.contains("within 5 minutes (no container for it in the project)"), told);

        // The same detail as an earlier release stored it, with what was seen in Docker's words.
        final String stored = "{\"stage\": \"FAILED\", \"notes\": [], \"services\": [{\"state\": \"FAILED\","
                + " \"changes\": [], \"service\": \"smp\", \"detail\": {\"key\": \"report.not-healthy\", \"args\":"
                + " {\"minutes\": {\"kind\": \"number\", \"value\": 5},"
                + " \"seen\": {\"kind\": \"text\", \"value\": \"running, unhealthy\"}}}}]}";
        final String english = UpdateReports.english(stored);
        assertTrue(english.contains("within 5 minutes (running, unhealthy)"), english);
    }

    @Test
    void aFailureSaysWhichServiceAndWhy() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(new UpdateReport.ServiceLine("limbo", UpdateReport.State.PLANNED, List.of(), null)
                        .failed(TEXTS.report().words("did not report healthy within 5 minutes")));

        assertEquals(UpdateReport.State.FAILED, report.line("limbo").state(), report.toString());
        assertEquals(
                TEXTS.report().words("did not report healthy within 5 minutes"),
                report.line("limbo").detail(),
                report.toString());
    }
}
