package eu.nordtal.season.steward.texts;

import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;

/** The words of the backups page. */
@Name("Backups page")
public interface BackupsPage {

    /** The series a backup copies, as the backup destination routes name them. */
    enum Series {
        STACK,
        PLANE
    }

    /** How a Google sign-in for a backup account ended. */
    enum GoogleResult {
        CONNECTED,
        DECLINED,
        EXPIRED,
        UNCONFIGURED,
        FAILED
    }

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

    @Name("Archive contents")
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

    @Name("No archive yet")
    MessageRef noArchiveYet();

    @Name("Archives not catalogued")
    MessageRef notCatalogued();

    @Name("Contents")
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

    @Name("Copies to Google Drive")
    MessageRef copies();

    @Name("Google Drive not set up")
    MessageRef copiesUnconfigured();

    @Name("How to set up Google Drive")
    MessageRef copiesUnconfiguredNote();

    @Name("Where a series copies to")
    MessageRef series(@Arg("series") Series series);

    @Name("No account chosen")
    MessageRef noAccount();

    @Name("Copied")
    MessageRef copied();

    @Name("Not copied yet")
    MessageRef notCopied();

    @Name("Last copy failed")
    MessageRef copyFailed();

    @Name("Destination saved")
    MessageRef destinationSaved();

    @Name("Google accounts")
    MessageRef accounts();

    @Name("No Google account")
    MessageRef noAccounts();

    @Name("Connect a Google account")
    MessageRef connect();

    @Name("An account's folder")
    MessageRef accountFolder(@Arg("folder") @Example("Nordtal backups") String folder);

    @Name("A disconnected account")
    MessageRef accountDisconnected();

    @Name("Download a recovery file")
    MessageRef recovery(@Arg("series") Series series);

    @Name("A recovery file taken")
    MessageRef recoveryTaken(@Arg("series") Series series);

    @Name("What a recovery file is")
    MessageRef recoveryNote();

    @Name("Disconnect")
    MessageRef disconnect();

    @Name("Disconnect an account?")
    MessageRef disconnectTitle(@Arg("email") @Example("backups@example.com") String email);

    @Name("What a disconnect does")
    MessageRef disconnectNote();

    @Name("Account disconnected")
    MessageRef disconnected();

    @Name("A Google sign-in's outcome")
    MessageRef googleResult(@Arg("result") GoogleResult result);
}
