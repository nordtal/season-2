package eu.nordtal.s2.commands;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Name;

/** What the bot announces by itself. */
@Name("Announce")
public interface Announce {

    @Name("Phase")
    MessageRef phase(@Arg("phase") Object phase, @Arg("previous") Object previous);
}
