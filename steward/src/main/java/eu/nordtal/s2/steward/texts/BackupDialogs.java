package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;
import java.util.List;

/** The words of the backup page's two dialogs. */
@Name("Backup settings")
public interface BackupDialogs {

    @Name("Destination")
    MessageRef destination();

    @Name("Destination, said")
    MessageRef destinationNote();

    @Name("No remote section")
    MessageRef noRemote();

    @Name("No remote section, said")
    MessageRef noRemoteNote();

    @Name("Destination saved")
    MessageRef destinationSaved();

    @Name("Schedule, said")
    MessageRef scheduleNote();

    @Name("No time")
    MessageRef noAt();

    @Name("No days")
    MessageRef noDays();

    @Name("No night")
    MessageRef noNight();

    @Name("Retention saved")
    MessageRef retentionSaved();

    @Name("Kept daily")
    MessageRef daily(@Arg("days") int days);

    @Name("Kept weekly")
    MessageRef weekly(@Arg("weeks") int weeks);

    @Name("Kept monthly")
    MessageRef monthly(@Arg("months") int months);

    @Name("Retention")
    MessageRef retention(
            @Arg("steps") List<String> steps,
            @Arg("total") int total,
            @Arg("sweep") boolean sweep,
            @Arg("days") int days);
}
