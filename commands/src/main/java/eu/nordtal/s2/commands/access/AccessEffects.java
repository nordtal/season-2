package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything {@code /access} touches that only the Discord bot can reach.
 *
 * <h2>Why these live in the bot and nowhere else</h2>
 * Granting access is three things at once: a row, a Discord role, and a direct message in the
 * recipient's own language. Only the bot holds a JDA session, so only the bot can do the second and
 * third - which is why {@link eu.nordtal.s2.commands.Target#BOT} is a target like any other rather
 * than a special case. A Paper server asking for one of these writes a {@code command_request} row
 * exactly as it would for any other process.
 *
 * <h2>What folding these fixed rather than moved</h2>
 * All four admin commands here were gated on Discord's own {@code DefaultMemberPermissions.DISABLED}
 * and on <b>nothing else</b> - no {@code discord_user.admin} read anywhere - so the network's admin
 * list and the list of people who could grant paid access were two different lists. Every line they
 * produced was also hardcoded English, in a bot whose whole message system exists so that nothing
 * is. Both are properties of a command now, checked once, for every command.
 */
public interface AccessEffects extends CommandEffects {

    /** One period of access somebody holds or held. */
    record Grant(Instant validFrom, Instant validUntil, String source, boolean revoked) {
    }

    /** One purchase, in whatever state it reached. */
    record Purchase(String reference, int days, String amount, String status) {
    }

    /** Everything {@code /access status} prints about one account. */
    record Status(String name, Optional<Instant> accessUntil, boolean donor, Locale locale,
                  Optional<UUID> minecraftAccount, List<Grant> grants, List<Purchase> purchases) {
    }

    /** Everything worth knowing about one account. Empty when Discord does not know the id. */
    Optional<Status> status(String discordId);

    /**
     * Add days of access, apply the role, and tell them.
     *
     * @return when access now runs until
     */
    Instant grant(String discordId, int days, NordtalUser by);

    /**
     * Take every running grant away, remove the role, and tell them.
     *
     * @return how many grants were revoked - zero is a legitimate answer and worth saying
     */
    int revoke(String discordId, NordtalUser by);

    /** Break the link between a Discord account and a Minecraft one. */
    boolean unlink(String discordId, NordtalUser by);

    /** Every payment reference still waiting to be settled, for the suggestions. */
    List<String> openReferences();

    /**
     * Book a payment by hand.
     *
     * @return what happened, and - when it was booked - what it bought. One value rather than a
     *         result plus a getter for the details, because a getter would be state, and state
     *         between two calls is what makes an effects implementation unusable from two threads
     */
    Settled settle(String reference, NordtalUser by);

    /**
     * The outcome of {@link #settle}.
     *
     * @param outcome  which of the three happened
     * @param until    when the access it bought runs until, only meaningful for
     *                 {@link Settlement#BOOKED}
     * @param days     how many days it bought
     * @param status   the status a {@link Settlement#NOT_OPEN} request was actually in, so the
     *                 refusal can name it - "already paid" and "cancelled" are different problems
     */
    record Settled(Settlement outcome, Instant until, int days, String status) {
    }

    /** The three ways {@link #settle} can end. */
    enum Settlement {

        /** No request carries that reference. */
        UNKNOWN,

        /** It exists and is not open, so there is nothing to book. */
        NOT_OPEN,

        /** Booked. */
        BOOKED
    }

    /** Re-read the bot's own message bundles and the operator's override. */
    boolean reloadMessages();

    /** Override keys the bundles do not declare, after a reload. Empty when there are none. */
    List<String> unknownOverrideKeys();
}
