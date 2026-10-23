package eu.nordtal.season.steward.texts;

import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.spec.Arg;
import eu.nordtal.season.messages.spec.Name;
import eu.nordtal.season.messages.value.Example;
import java.time.DayOfWeek;

/** The words that all settings dialogs share. */
@Name("Forms")
public interface Forms {

    @Name("Save")
    MessageRef save();

    @Name("Changed")
    MessageRef changed(@Arg("count") @Example("3") int count);

    @Name("Days")
    MessageRef days();

    @Name("Weekday")
    MessageRef weekday(@Arg("day") DayOfWeek day);

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

    @Name("Remove one by name")
    MessageRef removeNamed(@Arg("name") @Example("Chunky") String name);

    @Name("Removing")
    MessageRef removing();

    @Name("Reset")
    MessageRef reset();

    @Name("Saving")
    MessageRef saving();

    @Name("Save count")
    MessageRef saveCount(@Arg("count") @Example("3") int count);

    @Name("Discard")
    MessageRef discard();

    @Name("Done")
    MessageRef done();

    @Name("Sending")
    MessageRef sending();

    @Name("Waiting")
    MessageRef waiting();
}
