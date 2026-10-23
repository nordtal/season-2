package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The words of the backups page. */
@Name("Backups page")
public interface BackupsPage {

    @Name("Title")
    MessageRef title();

    @Name("Back up now")
    MessageRef backUpNow();

    @Name("Latest backup")
    MessageRef latest();

    @Name("None")
    MessageRef none();

    @Name("None finished")
    MessageRef noneFinished();

    @Name("Storage available")
    MessageRef storage();

    @Name("Not tracked")
    MessageRef notTracked();

    @Name("Next")
    MessageRef next();

    @Name("No clock")
    MessageRef noClock();

    @Name("Runs")
    MessageRef runs();

    @Name("No run yet")
    MessageRef noRun();

    @Name("Run")
    MessageRef run();

    @Name("When")
    MessageRef when();

    @Name("Status")
    MessageRef status();

    @Name("Archives")
    MessageRef archives();

    @Name("Took")
    MessageRef took();

    @Name("Initiated by")
    MessageRef initiatedBy();

    @Name("Restore")
    MessageRef restore();

    @Name("Nothing to restore")
    MessageRef nothingToRestore();

    @Name("All partial")
    MessageRef allPartial();

    @Name("Empty directory")
    MessageRef emptyDirectory();

    @Name("Archive")
    MessageRef archive();

    @Name("Choose an archive")
    MessageRef chooseArchive();

    @Name("Type to confirm")
    MessageRef type();

    @Name("Database replaced")
    MessageRef databaseReplaced();

    @Name("Volume replaced")
    MessageRef volumeReplaced();

    @Name("What a file holds")
    MessageRef holds(@Arg("subject") @Example("smp-world") String subject, @Arg("partial") boolean partial);

    @Name("An archive of a volume that left the backup")
    MessageRef left();

    @Name("The database")
    MessageRef database();

    @Name("Unknown")
    MessageRef unknown();

    @Name("A backup's title")
    MessageRef backup(@Arg("id") @Example("17") String id);

    @Name("All backups")
    MessageRef allBackups();

    @Name("No archive")
    MessageRef noArchive();

    @Name("Archives not catalogued")
    MessageRef notCatalogued();

    @Name("Holds")
    MessageRef holdsColumn();

    @Name("Written")
    MessageRef written();

    @Name("Size")
    MessageRef size();

    @Name("Download a file")
    MessageRef downloadFile(@Arg("file") @Example("nordtal-20261020-0400.dump") String file);

    @Name("An archive a prune removed")
    MessageRef pruned(@Arg("rule") RunStep.PruneRule rule);

    @Name("An archive missing from the disk")
    MessageRef missing();
}
