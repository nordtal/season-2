package eu.nordtal.s2.commands;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;

/** What {@code /announce} says back once it has posted, or explains why it did not. */
@Name("Announce")
public interface Announce {

    @Name("Posted")
    MessageRef posted(@Arg("language") Object language);

    @Name("Phase")
    MessageRef phase(@Arg("phase") Object phase, @Arg("previous") Object previous);

    @Name("No channel")
    MessageRef noChannel(@Arg("language") Object language);
}
