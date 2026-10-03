package eu.nordtal.s2.steward.texts;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;
import java.util.List;

/** The updates page's own words. */
@Name("Updates page")
public interface UpdatesPage {

    @Name("Title")
    MessageRef title();

    @Name("Check again, said")
    MessageRef checkAgainTip();

    @Name("Could not check again")
    MessageRef checkAgainFailed();

    @Name("Check again")
    MessageRef checkAgain();

    @Name("Restart everything")
    MessageRef restartEverything();

    @Name("Update everything")
    MessageRef updateEverything();

    @Name("Available")
    MessageRef available();

    @Name("A source silent")
    MessageRef sourceSilent();

    @Name("Checked")
    MessageRef checked();

    @Name("Next")
    MessageRef next();

    @Name("Not scheduled")
    MessageRef notScheduled();

    @Name("Nothing installed yet")
    MessageRef nothing();

    @Name("Nothing to install")
    MessageRef nothingToInstall();

    @Name("Nothing to install, said")
    MessageRef nothingToInstallNote();

    @Name("Incomplete")
    MessageRef incomplete();

    @Name("Service")
    MessageRef service();

    @Name("Plugin")
    MessageRef plugin();

    @Name("Change")
    MessageRef change();

    @Name("State")
    MessageRef state();

    @Name("The resource pack")
    MessageRef resourcePack();

    @Name("Claimed by nothing")
    MessageRef unclaimed(@Arg("files") List<String> files);

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

    @Name("Kind")
    MessageRef kind();

    @Name("Status")
    MessageRef status();

    @Name("Result")
    MessageRef result();

    @Name("Initiated by")
    MessageRef initiatedBy();

    @Name("Schedule, said")
    MessageRef scheduleNote();

    @Name("No update section")
    MessageRef noSection();

    @Name("No update section, said")
    MessageRef noSectionNote();

    @Name("No day")
    MessageRef noDay();
}
