package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;

/** The words every settings dialog shares. */
@Name("Forms")
public interface Forms {

    @Name("Save")
    MessageRef save();

    @Name("Changed")
    MessageRef changed(@Arg("count") int count);

    @Name("Changed meanwhile")
    MessageRef changedMeanwhile();

    @Name("Days")
    MessageRef days();

    @Name("Schedule")
    MessageRef schedule();

    @Name("Schedule saved")
    MessageRef scheduleSaved();

    @Name("Cancel")
    MessageRef cancel();

    @Name("Close")
    MessageRef close();

    @Name("Remove")
    MessageRef remove();

    @Name("Removing")
    MessageRef removing();

    @Name("Reset")
    MessageRef reset();

    @Name("Saving")
    MessageRef saving();

    @Name("Save count")
    MessageRef saveCount(@Arg("count") int count);

    @Name("Discard")
    MessageRef discard();

    @Name("Done")
    MessageRef done();

    @Name("Sending")
    MessageRef sending();

    @Name("Waiting")
    MessageRef waiting();
}
