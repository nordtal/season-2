package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;

/** Every sentence the Discord update confirmation can print, and the tables that turn a report's enums into one. */
@Name("Update")
public interface Update {

    @Name("Cancelled")
    MessageRef cancelled();

    @Name("Too late")
    MessageRef tooLate();

    @Name("Busy")
    MessageRef busy();

    @Name("Already down")
    MessageRef alreadyDown(@Arg("services") Object services);

    @Name("Gone")
    MessageRef gone();

    @Name("Timeout")
    MessageRef timeout(@Arg("status") Object status);

    @Name("Interaction failed")
    MessageRef interactionFailed();

    Embed embed();

    /** The one-word headings of a run drawn in Discord. */
    @Name("Embed")
    interface Embed {

        @Name("Services")
        MessageRef services();

        @Name("Notes")
        MessageRef notes();

        @Name("Duration")
        MessageRef duration();

        @Name("Run")
        MessageRef run();

        @Name("By")
        MessageRef by();

        @Name("From")
        MessageRef from();

        @Name("No build")
        MessageRef noBuild();

        @Name("More")
        MessageRef more(@Arg("count") Object count);
    }

    /** The headline for a stage. */
    default MessageRef stage(final UpdateReport.Stage stage) {
        return switch (stage) {
            case RESOLVING -> stage().resolving();
            case PLANNED -> stage().planned();
            case COUNTDOWN -> stage().countdown();
            case STOPPING -> stage().stopping();
            case BACKING_UP -> stage().backingUp();
            case INSTALLING -> stage().installing();
            case STARTING -> stage().starting();
            case VERIFYING -> stage().verifying();
            case DONE -> stage().done();
            case NOTHING_TO_DO -> stage().nothingToDo();
            case FAILED -> stage().failed();
            case CANCELLED -> stage().cancelled();
        };
    }

    /** A service's state on its own, for a column that already names the service. */
    default MessageRef state(final UpdateReport.State state) {
        return switch (state) {
            case UNCHANGED -> state().unchanged();
            case PLANNED -> state().planned();
            case STOPPED -> state().stopped();
            case INSTALLED -> state().installed();
            case SAVED -> state().saved();
            case STARTING -> state().starting();
            case HEALTHY -> state().healthy();
            case FAILED -> state().failed();
        };
    }

    /** The heading of a run, by what was asked for. */
    default MessageRef title(final UpdateKind kind) {
        return switch (kind) {
            case REPORT -> title().report();
            case APPLY -> title().apply();
            case UPDATE -> title().update();
            case RESTART -> title().restart();
            case BACKUP -> title().backup();
            case DOWN -> title().down();
            case START -> title().start();
        };
    }

    Stage stage();

    @Name("Stage")
    interface Stage {

        @Name("Resolving")
        @Key("RESOLVING")
        MessageRef resolving();

        @Name("Planned")
        @Key("PLANNED")
        MessageRef planned();

        @Name("Countdown")
        @Key("COUNTDOWN")
        MessageRef countdown();

        @Name("Stopping")
        @Key("STOPPING")
        MessageRef stopping();

        @Name("Backing up")
        @Key("BACKING_UP")
        MessageRef backingUp();

        @Name("Installing")
        @Key("INSTALLING")
        MessageRef installing();

        @Name("Starting")
        @Key("STARTING")
        MessageRef starting();

        @Name("Verifying")
        @Key("VERIFYING")
        MessageRef verifying();

        @Name("Done")
        @Key("DONE")
        MessageRef done();

        @Name("Nothing to do")
        @Key("NOTHING_TO_DO")
        MessageRef nothingToDo();

        @Name("Failed")
        @Key("FAILED")
        MessageRef failed();

        @Name("Cancelled")
        @Key("CANCELLED")
        MessageRef cancelled();
    }

    State state();

    @Name("State")
    interface State {

        @Name("Unchanged")
        @Key("UNCHANGED")
        MessageRef unchanged();

        @Name("Planned")
        @Key("PLANNED")
        MessageRef planned();

        @Name("Stopped")
        @Key("STOPPED")
        MessageRef stopped();

        @Name("Installed")
        @Key("INSTALLED")
        MessageRef installed();

        @Name("Saved")
        @Key("SAVED")
        MessageRef saved();

        @Name("Starting")
        @Key("STARTING")
        MessageRef starting();

        @Name("Healthy")
        @Key("HEALTHY")
        MessageRef healthy();

        @Name("Failed")
        @Key("FAILED")
        MessageRef failed();
    }

    Waiting waiting();

    @Name("Waiting")
    interface Waiting {

        @Name("Check")
        MessageRef check();
    }

    Countdown countdown();

    @Name("Countdown")
    interface Countdown {

        @Name("Started")
        MessageRef started(@Arg("seconds") Object seconds);
    }

    Button button();

    @Name("Button")
    interface Button {

        @Name("Install")
        MessageRef install();

        @Name("Restart")
        MessageRef restart();

        @Name("Cancel")
        MessageRef cancel();
    }

    Title title();

    @Name("Title")
    interface Title {

        @Name("Report")
        @Key("REPORT")
        MessageRef report();

        @Name("Update")
        @Key("UPDATE")
        MessageRef update();

        @Name("Restart")
        @Key("RESTART")
        MessageRef restart();

        @Name("Backup")
        @Key("BACKUP")
        MessageRef backup();

        @Name("Apply")
        @Key("APPLY")
        MessageRef apply();

        @Name("Down")
        @Key("DOWN")
        MessageRef down();

        @Name("Start")
        @Key("START")
        MessageRef start();
    }
}
