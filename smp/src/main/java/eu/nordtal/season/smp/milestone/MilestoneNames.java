package eu.nordtal.season.smp.milestone;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messages.Messages;
import java.util.Locale;

/**
 * What a milestone is called, for a player.
 *
 * A milestone without a shipped name, and every objective, shows its config key.
 */
public final class MilestoneNames {

    private MilestoneNames() {}

    /** Returns the milestone's name in {@code locale}, or {@code key} itself. */
    public static String of(final Messages messages, final Locale locale, final String key) {
        return MESSAGES.smp()
                .milestoneName(key)
                .map(name -> messages.format(locale, name))
                .orElse(key);
    }
}
