package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Arg;
import eu.nordtal.s2.common.message.spec.Name;

/** What {@code /backup now} says once it has asked for one. */
@Name("Backup")
public interface Backup {

    @Name("Started")
    MessageRef started(@Arg("seconds") Object seconds);
}
