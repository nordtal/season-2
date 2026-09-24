package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.update.RunRefused;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

/** The sentence for a run the directory refused, the same in game and on Discord. */
public final class Refusals {

    private Refusals() {
    }

    public static MessageRef of(final RunRefused refused) {
        return switch (refused.reason()) {
            case RUN_OPEN -> MESSAGES.update().busy();
            case ALREADY_HELD -> MESSAGES.update().alreadyDown(String.join(", ", refused.services()));
        };
    }
}
