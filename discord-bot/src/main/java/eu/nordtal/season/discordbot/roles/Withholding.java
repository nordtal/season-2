package eu.nordtal.season.discordbot.roles;

import net.dv8tion.jda.api.entities.Member;

/**
 * Decides whether the bot withholds the access and donor roles from a member.
 *
 * The onboarding lock decides it.
 */
@FunctionalInterface
public interface Withholding {

    Withholding NOBODY = member -> false;

    /** Returns whether the bot withholds the access and donor roles from {@code member}. */
    boolean withholds(Member member);
}
