package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Everything {@code /access} touches that only the Discord bot can reach: rows, roles and direct messages. */
public interface AccessEffects extends CommandEffects {

    /** One period of access somebody holds or held. */
    record Grant(Instant validFrom, Instant validUntil, String source, boolean revoked) {}

    /** One purchase, in whatever state it reached. */
    record Purchase(String reference, int days, String amount, String status) {}

    /** Everything {@code /access status} prints about one account. */
    record Status(
            String name,
            Optional<Instant> accessUntil,
            boolean donor,
            Locale locale,
            Optional<UUID> minecraftAccount,
            List<Grant> grants,
            List<Purchase> purchases) {}

    /** Returns everything worth knowing about one account, or empty when Discord does not know the id. */
    Optional<Status> status(String discordId);

    /**
     * Adds days of access, applies the role, and tells them.
     *
     * @return when access now runs until
     */
    Instant grant(String discordId, int days, NordtalUser by);

    /**
     * Takes every running grant away, removes the role, and tells them.
     *
     * @return how many grants were revoked, zero included
     */
    int revoke(String discordId, NordtalUser by);

    /** Breaks the link between a Discord account and a Minecraft one. */
    boolean unlink(String discordId, NordtalUser by);

    /** Returns every payment reference still waiting to be settled, for the suggestions. */
    List<String> openReferences();

    /**
     * Books a payment by hand.
     *
     * @return what happened and, when it was booked, what it bought
     */
    Settled settle(String reference, NordtalUser by);

    /**
     * The outcome of {@link #settle}.
     *
     * @param outcome  which of the three happened
     * @param until    when the access it bought runs until, for {@link Settlement#BOOKED} only
     * @param days     how many days it bought
     * @param status   the status a {@link Settlement#NOT_OPEN} request was actually in, so the refusal can name it
     */
    record Settled(
            Settlement outcome,
            @Nullable Instant until,
            int days,
            @Nullable String status) {}

    /** The three ways {@link #settle} can end. */
    enum Settlement {

        /** No request carries that reference. */
        UNKNOWN,

        /** It exists and is not open, so there is nothing to book. */
        NOT_OPEN,

        /** Booked. */
        BOOKED
    }

    /** Re-reads the bot's own message bundles and the operator's override. */
    boolean reloadMessages();

    /** Returns override keys the bundles do not declare, after a reload. */
    List<String> unknownOverrideKeys();
}
