package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The texts of the start page: the host figures, the latest backup, drift, issues and the latest actions. */
@Name("Status")
public interface OverviewPage {

    @Name("Cores")
    MessageRef cores(@Arg("count") @Example("8") int count);

    @Name("Memory")
    MessageRef memory();

    @Name("Used of")
    MessageRef usedOf(@Arg("used") @Example("9.3 GiB") String used, @Arg("total") @Example("16 GiB") String total);

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
}
