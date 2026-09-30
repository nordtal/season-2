package eu.nordtal.s2.commands;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.spec.Name;

/** Limbo's own admin reload. */
@Name("Limbo")
public interface Limbo {

    Admin admin();

    @Name("Admin")
    interface Admin {

        @Name("Reloaded")
        MessageRef reloaded();

        @Name("Reload failed")
        MessageRef reloadFailed();
    }
}
