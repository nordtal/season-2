package eu.nordtal.s2.database;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;

/** Every message of the database bundle: what a refused write tells whoever asked for it. */
@MessageSpec("database")
public interface DatabaseMessages {

    /** The messages; stateless, so one instance serves every caller. */
    DatabaseMessages MESSAGES = MessageSpecs.create(DatabaseMessages.class);

    UpdateRefusals update();

    SeasonRefusals season();

    @Name("Update runs")
    interface UpdateRefusals {

        @Name("A run is still open")
        MessageRef runOpen(@Arg("id") Object id, @Arg("kind") Object kind, @Arg("status") Object status);

        @Name("Already down")
        MessageRef alreadyHeld(@Arg("services") Object services);
    }

    @Name("Season dates")
    interface SeasonRefusals {

        @Name("Opening in the past")
        MessageRef launchInThePast(@Arg("at") Object at, @Arg("clear") Object clear);

        @Name("Paid time in the past")
        MessageRef smpStartInThePast(@Arg("at") Object at, @Arg("clear") Object clear);

        @Name("Opening after paid time")
        MessageRef launchAfterSmpStart(@Arg("at") Object at, @Arg("smp") Object smpStart);

        @Name("Paid time already running")
        MessageRef smpRunning();

        @Name("Paid time before the opening")
        MessageRef smpStartBeforeLaunch(@Arg("at") Object at, @Arg("launch") Object launch);
    }
}
