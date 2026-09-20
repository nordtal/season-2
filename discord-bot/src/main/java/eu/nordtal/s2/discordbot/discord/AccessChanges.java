package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.access.AccessEffects;

import java.time.Instant;

/**
 * The five things an access change can be, as the one process that can carry them out does them
 * (season-2-community/08).
 *
 * <h2>Why this is not {@code AccessEffects}</h2>
 * {@link AccessEffects} is a command's view: it carries a {@code NordtalUser}, a reply channel and a
 * permission answer, because a command has all three. A row in {@code access_request} has none of
 * them - the asker's HTTP call returned long before the bot read it - and the only thing the two
 * views share is what actually happens. This is that, and nothing else: five verbs, an {@link Actor}
 * and a result.
 *
 * <p>It is also what makes {@link AccessInbox} testable at all. The implementation is a JDA session,
 * a database and a guild; the dispatch is five cases and a JSON line, and the second one should not
 * need the first one to be exercised.</p>
 */
public interface AccessChanges {

    /** Add days of access, apply the role, and tell them. @return when access now runs until */
    Instant grant(String discordId, int days, Actor by);

    /** Take every running grant away, remove the role, and tell them. @return how many went */
    int revoke(String discordId, Actor by);

    /** Break the link between a Discord account and a Minecraft one. */
    boolean unlink(String discordId, Actor by);

    /** Book a payment by hand. */
    AccessEffects.Settled settle(String reference, Actor by);

    /** Write somebody's total play time, in seconds - the unit the column holds. */
    void setPlaytime(String discordId, long seconds, Actor by);
}
