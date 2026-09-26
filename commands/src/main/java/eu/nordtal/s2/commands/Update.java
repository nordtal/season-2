package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.ServiceContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Key;
import eu.nordtal.s2.common.message.spec.Name;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;

/** Every sentence {@code /update} can print, and the tables that turn a report's enums into one. */
@Name("Update")
public interface Update {

    @Name("Asked")
    MessageRef asked();

    @Name("Started")
    MessageRef started(@Arg("seconds") Object seconds);

    @Name("Cancelled")
    MessageRef cancelled();

    @Name("Too late")
    MessageRef tooLate();

    @Name("Write failed")
    MessageRef writeFailed();

    @Name("Busy")
    MessageRef busy();

    @Name("Already down")
    MessageRef alreadyDown(@Arg("services") Object services);

    @Name("Truncated")
    MessageRef truncated(@Arg("lines") Object lines);

    @Name("Failed")
    MessageRef failed();

    @Name("Gone")
    MessageRef gone();

    @Name("Timeout")
    MessageRef timeout(@Arg("status") Object status);

    @Name("Stopped by")
    MessageRef stoppedBy(@Arg("reason") Object reason);

    @Name("Change")
    MessageRef change(@Arg("artefact") Object artefact, @Arg("from") Object from, @Arg("to") Object to);

    @Name("Detail")
    MessageRef detail(@Arg("detail") Object detail);

    @Name("Note")
    MessageRef note(@Arg("note") Object note);

    @Name("Interaction failed")
    MessageRef interactionFailed();

    Down down();

    @Name("Down")
    interface Down {

        @Name("Asked")
        MessageRef asked(@Arg("service") ServiceContext service, @Arg("seconds") Object seconds);
    }

    Start start();

    @Name("Start")
    interface Start {

        @Name("Asked")
        MessageRef asked();
    }

    Embed embed();

    /**
     * The headings of a run drawn in Discord.
     *
     * One word each: the value under a heading is the data, and a heading that explains it is a
     * sentence in a box.
     */
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

    /** A service's state with its name, for chat. */
    default MessageRef line(final UpdateReport.State state, final ServiceContext service) {
        return switch (state) {
            case UNCHANGED -> line().unchanged(service);
            case PLANNED -> line().planned(service);
            case STOPPED -> line().stopped(service);
            case INSTALLED -> line().installed(service);
            case SAVED -> line().saved(service);
            case STARTING -> line().starting(service);
            case HEALTHY -> line().healthy(service);
            case FAILED -> line().failed(service);
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

    Line line();

    @Name("Line")
    interface Line {

        @Name("Unchanged")
        @Key("UNCHANGED")
        MessageRef unchanged(@Arg("service") ServiceContext service);

        @Name("Planned")
        @Key("PLANNED")
        MessageRef planned(@Arg("service") ServiceContext service);

        @Name("Stopped")
        @Key("STOPPED")
        MessageRef stopped(@Arg("service") ServiceContext service);

        @Name("Installed")
        @Key("INSTALLED")
        MessageRef installed(@Arg("service") ServiceContext service);

        @Name("Saved")
        @Key("SAVED")
        MessageRef saved(@Arg("service") ServiceContext service);

        @Name("Starting")
        @Key("STARTING")
        MessageRef starting(@Arg("service") ServiceContext service);

        @Name("Healthy")
        @Key("HEALTHY")
        MessageRef healthy(@Arg("service") ServiceContext service);

        @Name("Failed")
        @Key("FAILED")
        MessageRef failed(@Arg("service") ServiceContext service);
    }

    @Key("change")
    Change changeSection();

    @Name("Change")
    interface Change {

        @Name("New")
        @Key("new")
        MessageRef newMessage(@Arg("artefact") Object artefact, @Arg("to") Object to);

        @Name("Unsupported")
        MessageRef unsupported(@Arg("artefact") Object artefact);
    }

    Waiting waiting();

    @Name("Waiting")
    interface Waiting {

        @Name("Check")
        MessageRef check();

        @Name("Now")
        MessageRef now();
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
