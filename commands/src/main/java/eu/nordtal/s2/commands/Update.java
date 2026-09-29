package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.update.UpdateReport;

/** The words of an update run drawn in the admin channel, and the tables that turn a report's enums into them. */
@Name("Update")
public interface Update {

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
}
