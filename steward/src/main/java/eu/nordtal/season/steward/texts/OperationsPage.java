package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.util.List;

/** The words of the run page and of the run request. The admin bundle holds the run texts. */
@Name("Operations page")
public interface OperationsPage {

    @Name("A stage on the trail")
    MessageRef step(@Arg("stage") RunStage stage);

    @Name("Nothing written yet")
    MessageRef nothingWritten();

    @Name("Nothing to do")
    MessageRef nothingToDo();

    @Name("Saved")
    MessageRef saved(@Arg("count") @Example("3") int count);

    @Name("Moved")
    MessageRef moved(@Arg("services") @Example("2") int services, @Arg("artefacts") @Example("4") int artefacts);

    @Name("Failed")
    MessageRef failed(@Arg("count") @Example("1") int count);

    @Name("No change")
    MessageRef noChange();

    @Name("Ask for a run")
    MessageRef ask(@Arg("kind") UpdateKind kind);

    @Name("What a run costs")
    MessageRef askWarning(@Arg("kind") @Example("restart") UpdateKind kind);

    @Name("Local builds an update replaces")
    MessageRef localBuilds(@Arg("builds") @Example("smp,proxy") List<String> builds);

    @Name("For some services")
    MessageRef scoped(
            @Arg("ask") @Example("Update") String ask, @Arg("services") @Example("smp,proxy") List<String> services);

    @Name("Now")
    MessageRef now();

    @Name("Entered")
    MessageRef entered(@Arg("kind") @Example("Update") String kind, @Arg("run") @Example("42") long run);

    @Name("Cancelled")
    MessageRef cancelled(@Arg("run") @Example("42") long run);

    @Name("Cancelled, said")
    MessageRef cancelledNote();

    @Name("Not cancelled")
    MessageRef notCancelled(@Arg("run") @Example("42") long run);

    @Name("Cancel")
    MessageRef cancel();

    @Name("A run's title")
    MessageRef run(@Arg("id") @Example("42") String id);

    @Name("A run")
    MessageRef aRun();

    @Name("All updates")
    MessageRef allUpdates();

    @Name("Not a run number")
    MessageRef notANumber();

    @Name("Requested by")
    MessageRef requestedBy();

    @Name("No earlier than")
    MessageRef noEarlierThan();

    @Name("Started")
    MessageRef started();

    @Name("Duration")
    MessageRef duration();

    @Name("Still running")
    MessageRef stillRunning();

    @Name("Saved nothing")
    MessageRef savedNothing();

    @Name("Stages")
    MessageRef stages();

    @Name("State")
    MessageRef state();

    @Name("Notes")
    MessageRef notes();

    @Name("A stage on the rail")
    MessageRef stage(@Arg("stage") Stage stage);

    @Name("Services, counted")
    MessageRef services(@Arg("count") @Example("5") int count);

    @Name("Players moved")
    MessageRef playersMoved(@Arg("count") @Example("4") int count, @Arg("to") @Example("limbo") String to);

    @Name("Players back")
    MessageRef playersBack(@Arg("count") @Example("3") int count);

    @Name("Standby up")
    MessageRef standbyUp(@Arg("time") @Example("04:00") String time);

    @Name("Standby down")
    MessageRef standbyDown(@Arg("time") @Example("04:06") String time);

    @Name("Archives saved")
    MessageRef archivesSaved(@Arg("count") @Example("4") int count);

    @Name("Archives pruned")
    MessageRef archivesPruned(@Arg("count") @Example("2") int count);

    @Name("Images removed")
    MessageRef imagesRemoved(@Arg("count") @Example("3") int count);

    @Name("Freed")
    MessageRef freed(@Arg("size") @Example("1.2 GB") String size);

    @Name("Added")
    MessageRef added(@Arg("size") @Example("240 MB") String size);

    @Name("Under way")
    MessageRef underWay(@Arg("kind") @Example("Backup") String kind, @Arg("run") @Example("42") int run);

    /** A stage on the run page's rail, which gathers the step kinds of one part of a run. */
    enum Stage {
        RESOLVE,
        STANDBY,
        COUNTDOWN,
        PLAYERS,
        STOP,
        BACKUP,
        MIGRATE,
        INSTALL,
        RESTORE,
        STARTING,
        CLEANUP,
        OFFSITE
    }
}
