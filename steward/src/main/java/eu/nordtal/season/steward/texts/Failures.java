package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** What a page shows when an answer did not come, and the errors the interface raises itself. */
@Name("Failure")
public interface Failures {

    @Name("Docker silent")
    MessageRef dockerSilent();

    @Name("Agent silent")
    MessageRef agentSilent();

    @Name("Not loaded")
    MessageRef notLoaded();

    @Name("HTTP status")
    MessageRef http(@Arg("status") int status);

    @Name("Try again")
    MessageRef tryAgain();

    @Name("Not requested")
    MessageRef notRequested();

    @Name("Unreachable")
    MessageRef unreachable();

    @Name("Log lost")
    MessageRef logLost();

    @Name("Bot failed")
    MessageRef botFailed();

    @Name("Bot silent")
    MessageRef botSilent();

    @Name("No points")
    MessageRef noPoints();
}
