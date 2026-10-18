package eu.nordtal.season.smp.milestone;

import static eu.nordtal.season.smp.SmpMessages.MESSAGES;

import eu.nordtal.season.messages.Messages;
import java.util.Locale;

/** Resolves the name a player sees for a milestone. */
public final class MilestoneNames {

    private MilestoneNames() {}

    /** Returns the name of the milestone in {@code locale}, or {@code key} when no name exists. */
    public static String of(final Messages messages, final Locale locale, final String key) {
        return MESSAGES.smp()
                .milestoneName(key)
                .map(name -> messages.format(locale, name))
                .orElse(key);
    }
}
