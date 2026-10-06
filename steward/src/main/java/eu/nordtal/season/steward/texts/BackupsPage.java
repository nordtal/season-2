package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The backups page's own words. */
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

    @Name("Not tracked, said")
    MessageRef notTrackedNote();

    @Name("Next")
    MessageRef next();

    @Name("No clock")
    MessageRef noClock();

    @Name("Runs")
    MessageRef runs();

    @Name("No run yet")
    MessageRef noRun();

    @Name("No run yet, said")
    MessageRef noRunNote();

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

    @Name("Restore, said")
    MessageRef restoreNote();

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
    MessageRef holds(@Arg("subject") String subject, @Arg("partial") boolean partial);

    @Name("An archive of a volume that left the backup")
    MessageRef left();

    @Name("The database")
    MessageRef database();

    @Name("Unknown")
    MessageRef unknown();

    @Name("A backup's title")
    MessageRef backup(@Arg("id") String id);

    @Name("No such run")
    MessageRef noSuchRun();

    @Name("No such run, said")
    MessageRef noSuchRunNote();

    @Name("No archive")
    MessageRef noArchive();

    @Name("Holds")
    MessageRef holdsColumn();

    @Name("Written")
    MessageRef written();

    @Name("Size")
    MessageRef size();

    @Name("Download")
    MessageRef download();

    @Name("Download a file")
    MessageRef downloadFile(@Arg("file") String file);
}
