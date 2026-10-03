package eu.nordtal.s2.database;

import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.value.Example;
import java.time.Instant;
import java.util.List;

/** Every message of the database bundle: what a refused write tells whoever asked for it. */
@MessageSpec("database")
public interface DatabaseMessages {

    /** The messages; stateless, so one instance serves every caller. */
    DatabaseMessages MESSAGES = MessageSpecs.create(DatabaseMessages.class);

    UpdateRefusals update();

    SeasonRefusals season();

    ServerRefusals server();

    @Name("Update runs")
    interface UpdateRefusals {

        @Name("A run is still open")
        MessageRef runOpen(
                @Arg("id") long id,
                @Arg("kind") @Example("update") UpdateKind kind,
                @Arg("status") @Example("pending") UpdateStatus status);

        @Name("Already down")
        MessageRef alreadyHeld(@Arg("services") List<String> services);
    }

    @Name("Season dates")
    interface SeasonRefusals {

        @Name("Opening in the past")
        MessageRef launchInThePast(@Arg("at") Instant at, @Arg("clear") String clear);

        @Name("Paid time in the past")
        MessageRef smpStartInThePast(@Arg("at") Instant at, @Arg("clear") String clear);

        @Name("Opening after paid time")
        MessageRef launchAfterSmpStart(@Arg("at") Instant at, @Arg("smp") Instant smpStart);

        @Name("Paid time already running")
        MessageRef smpRunning();

        @Name("Paid time before the opening")
        MessageRef smpStartBeforeLaunch(@Arg("at") Instant at, @Arg("launch") Instant launch);
    }

    @Name("Server actions")
    interface ServerRefusals {

        @Name("No active milestone")
        MessageRef noActiveMilestone();

        @Name("No such objective")
        MessageRef noSuchObjective(@Arg("key") String key);

        @Name("Milestone not active")
        MessageRef milestoneNotActive(@Arg("key") String key, @Arg("active") String active);

        @Name("Wrong phase")
        MessageRef wrongPhase(@Arg("phase") String phase);

        @Name("No game")
        MessageRef noGame();

        @Name("Wrong state")
        MessageRef wrongState(@Arg("state") String state);

        @Name("Below the hard minimum")
        MessageRef belowHardMinimum(@Arg("minimum") long minimum, @Arg("count") long count);

        @Name("Below the recommended minimum")
        MessageRef belowSoftMinimum(@Arg("count") long count, @Arg("minimum") long minimum);
    }
}
