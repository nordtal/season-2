package eu.nordtal.season.database.update;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateReport.Facts;
import eu.nordtal.season.database.update.UpdateReport.ServiceLine;
import eu.nordtal.season.database.update.UpdateReport.State;
import eu.nordtal.season.messages.MessageRef;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Holds the short message of a service line, drawn from its typed facts, and the same on every surface. */
class ServiceLineFactsTest {

    private static final Facts.Seen STARTING = new Facts.Seen("running", "starting", null);

    private static ServiceLine line(final State state, final @Nullable Facts facts) {
        return new ServiceLine("smp", state, List.of(), facts);
    }

    private static @Nullable String said(final ServiceLine line) {
        final MessageRef said = line.said();
        return said == null ? null : DatabaseText.english(said);
    }

    @Test
    void aServiceThatDidNotComeBackSaysHowLongWasWaitedAndWhatWasSeen() {
        assertEquals(
                "not back within 5 minutes (running, starting)",
                said(line(State.FAILED, Facts.notBack(UpdateReport.Back.NO, 5L, STARTING))));
        assertEquals(
                "not back within 5 minutes (no container for it in the project)",
                said(line(State.FAILED, Facts.notBack(UpdateReport.Back.NO, 5L, new Facts.Seen(null, null, null)))));
        assertEquals(
                "not back within 1 minute (the container runtime could not be read: timed out)",
                said(line(
                        State.FAILED,
                        Facts.notBack(UpdateReport.Back.NO, 1L, new Facts.Seen(null, null, "timed out")))));
        assertEquals(
                "steward-agent stopped while waiting for this service",
                said(line(State.FAILED, Facts.notBack(UpdateReport.Back.INTERRUPTED, null, null))));
    }

    @Test
    void aRefusedRecreateSaysWhatTheServiceIsLeftOn() {
        final Facts refused =
                Facts.notRecreated(UpdateReport.Image.OUTDATED, "no such image", UpdateReport.Fallback.STARTED, null);
        assertEquals(
                "image out of date, not recreated: no such image; started on the old image, not seen back yet",
                said(line(State.FAILED, refused)));
        assertEquals(
                "image out of date, not recreated: no such image; back on the old image",
                said(line(State.FAILED, refused.came(UpdateReport.Back.YES, null, null))));
        assertEquals(
                "image out of date, not recreated: no such image; on the old image and not back within 5 minutes"
                        + " (running, starting)",
                said(line(State.FAILED, refused.came(UpdateReport.Back.NO, 5L, STARTING))));
        assertEquals(
                "image out of date, not recreated: no such image; started on the old image, and steward-agent"
                        + " stopped before it came back",
                said(line(State.FAILED, refused.came(UpdateReport.Back.INTERRUPTED, null, null))));
        assertEquals(
                "not recreated: no such image; starting the old image failed too: gone",
                said(line(
                        State.FAILED,
                        Facts.notRecreated(
                                UpdateReport.Image.KEPT, "no such image", UpdateReport.Fallback.REFUSED, "gone"))));
        assertEquals(
                "not recreated and nothing to go back to: no such image",
                said(line(
                        State.FAILED,
                        Facts.notRecreated(
                                UpdateReport.Image.PULLED, "no such image", UpdateReport.Fallback.NONE, null))));
        assertEquals(
                "not recreated, so the old one still runs: no such image",
                said(line(
                        State.FAILED,
                        Facts.notRecreated(
                                UpdateReport.Image.PULLED, "no such image", UpdateReport.Fallback.RUNNING, null))));
    }

    @Test
    void aRecreateSaysItWhileItRunsAndNothingOnceTheServiceIsBack() {
        final Facts recreating = Facts.of(UpdateReport.Act.RECREATE).made(UpdateReport.Image.PULLED);
        assertEquals("pulling its image and recreating the container", said(line(State.STARTING, recreating)));
        assertEquals(
                "recreating the container",
                said(line(State.STARTING, Facts.of(UpdateReport.Act.RECREATE).made(UpdateReport.Image.KEPT))));
        assertNull(said(line(State.HEALTHY, recreating)), "a container made and back has nothing left to say");
        assertEquals(
                "recreating the container at this release",
                said(line(State.STARTING, Facts.of(UpdateReport.Act.RENEW))));
        assertNull(said(line(State.HEALTHY, Facts.of(UpdateReport.Act.RENEW))));
    }

    @Test
    void aStopOrAStartThatFailedSaysWhy() {
        assertEquals(
                "could not be stopped: refused",
                said(line(State.FAILED, Facts.refused(UpdateReport.Act.STOP, "refused"))));
        assertEquals(
                "no container, so it was not stopped and nothing was installed for it",
                said(line(State.FAILED, Facts.refused(UpdateReport.Act.STOP, null))));
        assertEquals(
                "how it ended is unknown",
                said(line(State.STOPPED, Facts.refused(UpdateReport.Act.STOP, "how it ended is unknown"))),
                "an unverified stop keeps what the runtime answered beside a line that did stop");
        assertEquals(
                "could not be started: refused",
                said(line(State.FAILED, Facts.refused(UpdateReport.Act.START, "refused"))));
        assertEquals(
                "no container id to start it with",
                said(line(State.FAILED, Facts.refused(UpdateReport.Act.START, null))));
        assertEquals("smp: no space left", said(line(State.FAILED, Facts.notInstalled("smp", "no space left"))));
    }

    @Test
    void anArchiveSavedAfterAnUnverifiedStopNamesItsMark() {
        final String archive = "nordtal-s2_mc-smp-20261012T024500Z.tar.zst";
        assertNull(said(line(State.SAVED, Facts.saved(archive, null))), "a clean archive needs no word");
        assertEquals(
                "stop not verified, marked beside " + archive,
                said(line(State.SAVED, Facts.saved(archive, UpdateReport.Mark.WRITTEN))));
        assertEquals(
                "stop not verified, and the mark beside " + archive + " was not written",
                said(line(State.SAVED, Facts.saved(archive, UpdateReport.Mark.NOT_WRITTEN))));
        assertEquals("disk full", said(line(State.FAILED, Facts.refused(UpdateReport.Act.BACKUP, "disk full"))));
    }

    @Test
    void aRestoredArchiveSaysRestoredAndNotUpdated() {
        assertEquals("restored", DatabaseText.english(TEXTS.run().state(State.RESTORED)));
    }

    @Test
    void aFailedRunExplainsItselfByWhatItsLinesSay() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(line(State.FAILED, Facts.refused(UpdateReport.Act.START, "refused")))
                .with(new ServiceLine("limbo", State.HEALTHY, List.of(), null));

        assertEquals(
                List.of("smp could not be started: refused"),
                report.failures().stream()
                        .map(failure -> failure.service() + " " + DatabaseText.english(failure.what()))
                        .toList());
        assertEquals(Map.of("smp", "could not be started: refused"), englishOf(report.said()));
    }

    @Test
    void theFactsRoundTripThroughTheStoredReport() {
        final UpdateReport report = UpdateReport.at(UpdateReport.Stage.FAILED)
                .with(line(
                        State.FAILED,
                        Facts.notRecreated(UpdateReport.Image.OUTDATED, "why", UpdateReport.Fallback.STARTED, null)
                                .came(UpdateReport.Back.NO, 5L, STARTING)));

        assertEquals(report, UpdateReports.parse(UpdateReports.toJson(report)).orElseThrow());
    }

    private static Map<String, String> englishOf(final Map<String, MessageRef> said) {
        final Map<String, String> english = new java.util.LinkedHashMap<>();
        said.forEach((service, message) -> english.put(service, DatabaseText.english(message)));
        return english;
    }
}
