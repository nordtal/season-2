package eu.nordtal.s2.commands;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Name;

/** The network's own admin reload. */
@Name("Network")
public interface Network {

    @Name("Reloaded")
    MessageRef reloaded();

    @Name("Reload failed")
    MessageRef reloadFailed();
}
