package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.context.TeamContext;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Name;

/** Hunger Games: readiness, starting a game, and the admin reload. */
@Name("Hunger Games")
public interface Hg {

    Admin admin();

    @Name("Admin")
    interface Admin {

        @Name("Reload failed")
        MessageRef reloadFailed();

        @Name("Reloaded")
        MessageRef reloaded();
    }

    ReadyStatus readyStatus();

    @Name("Ready status")
    interface ReadyStatus {

        @Name("Header")
        MessageRef header();

        @Name("Line")
        MessageRef line(@Arg("team") TeamContext team, @Arg("status") Object status);

        @Name("Not ready")
        MessageRef notReady();

        @Name("Ready")
        MessageRef ready();
    }

    Start start();

    @Name("Start")
    interface Start {

        @Name("Below hard minimum")
        MessageRef belowHardMinimum(@Arg("minimum") Object minimum, @Arg("count") Object count);

        @Name("Below soft minimum")
        MessageRef belowSoftMinimum(
                @Arg("count") Object count, @Arg("minimum") Object minimum, @Arg("seconds") Object seconds);

        @Name("Confirm expired")
        MessageRef confirmExpired();

        @Name("No game")
        MessageRef noGame();

        @Name("Started")
        MessageRef started(@Arg("count") Object count);

        @Name("Wrong state")
        MessageRef wrongState(@Arg("state") Object state);

        @Name("Read failed")
        MessageRef readFailed();

        @Name("Failed")
        MessageRef failed();
    }
}
