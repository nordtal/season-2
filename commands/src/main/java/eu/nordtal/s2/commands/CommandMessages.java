package eu.nordtal.s2.commands;

import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecs;

/** Every message of the commands bundle, one method per key. */
@MessageSpec("commands")
public interface CommandMessages {

    /** The messages; stateless, so one instance serves every caller. */
    CommandMessages MESSAGES = MessageSpecs.create(CommandMessages.class);

    Command command();

    Smp smp();

    Hg hg();

    Limbo limbo();

    Network network();

    Announce announce();

    Update update();
}
