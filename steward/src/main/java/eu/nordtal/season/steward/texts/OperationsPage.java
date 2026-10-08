package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import java.util.List;

/** The words of a run's page and of asking for one; a run's own words are the admin bundle's. */
@Name("Operations page")
public interface OperationsPage {

    @Name("A stage on the trail")
    MessageRef step(@Arg("stage") UpdateReport.Stage stage);

    @Name("Report unreadable")
    MessageRef reportUnreadable();

    @Name("Nothing written yet")
    MessageRef nothingWritten();

    @Name("Nothing to do")
    MessageRef nothingToDo();

    @Name("Saved")
    MessageRef saved(@Arg("count") int count);

    @Name("Moved")
    MessageRef moved(@Arg("services") int services, @Arg("artefacts") int artefacts);

    @Name("Failed")
    MessageRef failed(@Arg("count") int count);

    @Name("No line")
    MessageRef noLine();

    @Name("No change")
    MessageRef noChange();

    @Name("Ask for a run")
    MessageRef ask(@Arg("kind") UpdateKind kind);

    @Name("What a run does")
    MessageRef askWhat(@Arg("kind") UpdateKind kind);

    @Name("What a run costs")
    MessageRef askWarning(@Arg("kind") UpdateKind kind);

    @Name("Local builds an update replaces")
    MessageRef localBuilds(@Arg("builds") List<String> builds);

    @Name("For some services")
    MessageRef scoped(@Arg("ask") String ask, @Arg("services") List<String> services);

    @Name("Now")
    MessageRef now();

    @Name("Entered")
    MessageRef entered(@Arg("kind") String kind, @Arg("run") long run);

    @Name("Cancelled")
    MessageRef cancelled(@Arg("run") long run);

    @Name("Cancelled, said")
    MessageRef cancelledNote();

    @Name("Not cancelled")
    MessageRef notCancelled(@Arg("run") long run);

    @Name("Cancel")
    MessageRef cancel();

    @Name("A run's title")
    MessageRef run(@Arg("id") String id);

    @Name("A run")
    MessageRef aRun();

    @Name("All updates")
    MessageRef allUpdates();

    @Name("Not a run number")
    MessageRef notANumber();

    @Name("Not a run number, said")
    MessageRef notANumberNote(@Arg("id") String id);

    @Name("Status")
    MessageRef status();

    @Name("Requested by")
    MessageRef requestedBy();

    @Name("No earlier than")
    MessageRef noEarlierThan();

    @Name("No earlier than, said")
    MessageRef noEarlierThanHint();

    @Name("Started")
    MessageRef started();

    @Name("Duration")
    MessageRef duration();

    @Name("Still running")
    MessageRef stillRunning();

    @Name("Nothing to do, as a title")
    MessageRef nothingToDoTitle();

    @Name("Nothing to do, said")
    MessageRef nothingToDoNote();

    @Name("Saved nothing")
    MessageRef savedNothing();

    @Name("Saved nothing, said")
    MessageRef savedNothingNote();

    @Name("Stages")
    MessageRef stages();

    @Name("Growing")
    MessageRef growing();

    @Name("Report")
    MessageRef report();

    @Name("Raw report")
    MessageRef rawReport();

    @Name("No report")
    MessageRef noReport();

    @Name("No report, said")
    MessageRef noReportNote();

    @Name("No line, as a title")
    MessageRef noLineTitle();

    @Name("No line, said")
    MessageRef noLineNote();

    @Name("Service")
    MessageRef service();

    @Name("State")
    MessageRef state();

    @Name("Changes")
    MessageRef changes();

    @Name("No build")
    MessageRef noBuild();

    @Name("New")
    MessageRef added();

    @Name("Notes")
    MessageRef notes();

    @Name("Outcome filter")
    MessageRef outcome();

    @Name("Every note")
    MessageRef allNotes();

    @Name("Copied")
    MessageRef copied();

    @Name("Copy")
    MessageRef copy();

    @Name("Command copied")
    MessageRef commandCopied();

    @Name("Cannot copy")
    MessageRef cannotCopy();

    @Name("Cannot copy, said")
    MessageRef cannotCopyNote();

    @Name("Under way")
    MessageRef underWay(@Arg("kind") String kind, @Arg("run") int run);
}
