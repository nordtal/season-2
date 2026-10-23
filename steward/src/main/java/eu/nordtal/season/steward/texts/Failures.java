package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The texts for a missing answer and for the errors that the interface raises. */
@Name("Failure")
public interface Failures {

    @Name("Docker silent")
    MessageRef dockerSilent();

    @Name("Agent silent")
    MessageRef agentSilent();

    @Name("Not loaded")
    MessageRef notLoaded();

    @Name("Not done")
    MessageRef notDone();

    @Name("HTTP status")
    MessageRef http(@Arg("status") int status);

    @Name("Try again")
    MessageRef tryAgain();

    @Name("Loading")
    MessageRef loading();

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

    @Name("SMP failed")
    MessageRef smpFailed();

    @Name("SMP silent")
    MessageRef smpSilent();
}
