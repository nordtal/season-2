package eu.nordtal.season.discordbot.roles;

import net.dv8tion.jda.api.entities.Member;

/**
 * Whether the bot holds back the roles it grants itself, the access and donor roles, from a member right now.
 *
 * The onboarding's lock decides it, so a locked member sees only what their language role opens.
 */
@FunctionalInterface
public interface Withholding {

    /** Withholds from nobody. */
    Withholding NOBODY = member -> false;

    /** Returns whether {@code member} is kept from the roles the bot grants itself. */
    boolean withholds(Member member);
}
