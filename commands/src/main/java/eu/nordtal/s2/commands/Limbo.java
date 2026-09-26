package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.spec.Name;

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
