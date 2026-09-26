package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Name;

/** The network's own admin reload. */
@Name("Network")
public interface Network {

    @Name("Reloaded")
    MessageRef reloaded();

    @Name("Reload failed")
    MessageRef reloadFailed();
}
