package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The start page: the host's figures, the latest backup, drift, issues and the latest actions. */
@Name("Status")
public interface OverviewPage {

    @Name("Cores")
    MessageRef cores(@Arg("count") int count);

    @Name("Memory")
    MessageRef memory();

    @Name("Used of")
    MessageRef usedOf(@Arg("used") String used, @Arg("total") String total);

    @Name("Latest backup")
    MessageRef latestBackup();

    @Name("None")
    MessageRef none();

    @Name("No finished backup")
    MessageRef noFinishedBackup();

    @Name("Behind")
    MessageRef behind();

    @Name("Issues")
    MessageRef issues();

    @Name("Unreadable")
    MessageRef unreadable();

    @Name("Partly unreadable")
    MessageRef partlyUnreadable();

    @Name("Latest actions")
    MessageRef latestActions();

    @Name("Nothing recorded")
    MessageRef nothingRecorded();

    @Name("Whole journal")
    MessageRef wholeJournal();

    @Name("Authored")
    MessageRef authored();
}
