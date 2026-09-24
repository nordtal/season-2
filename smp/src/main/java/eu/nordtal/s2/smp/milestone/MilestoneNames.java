package eu.nordtal.s2.smp.milestone;

import eu.nordtal.s2.common.message.Messages;

import java.util.Locale;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

/**
 * What a milestone is called, for a player.
 *
 * <p>The track is config, so a milestone the bundle ships no name for is shown under its config key.
 * Objectives have no shipped names at all and always show their key.</p>
 */
public final class MilestoneNames {

    private MilestoneNames() {
    }

    /** @return the milestone's name in {@code locale}, or {@code key} itself */
    public static String of(final Messages messages, final Locale locale, final String key) {
        return MESSAGES.smp().milestoneName(key).map(name -> messages.format(locale, name)).orElse(key);
    }
}
