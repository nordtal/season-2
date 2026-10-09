package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Name;

/** The words of the backup page's schedule dialog. */
@Name("Backup settings")
public interface BackupDialogs {

    @Name("No time")
    MessageRef noAt();

    @Name("No days")
    MessageRef noDays();

    @Name("No night")
    MessageRef noNight();

    @Name("Retention saved")
    MessageRef retentionSaved();
}
