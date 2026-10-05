package eu.nordtal.season.database.access;

import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.value.DisplayName;
import eu.nordtal.season.messages.value.Glyph;
import java.time.Duration;
import java.util.Objects;

/**
 * The card a player's name carries in game, shown on hover: their role, their crest and its tier, and their play time.
 * A server draws it from the identity it holds and the proxy from its login roster, so it is built here once.
 */
public final class PlayerCard {

    private PlayerCard() {}

    /** Returns the card of {@code name} from the identity a server holds of that player. */
    public static MessageRef of(final DisplayName name, final PlayerIdentity identity, final Prestige prestige) {
        return of(name, identity.admin(), identity.donor(), identity.playtimeSeconds(), prestige);
    }

    /**
     * Returns the card of {@code name}.
     *
     * @param admin           whether the account carries the admin flag, which wins over the donor's
     * @param donor           whether the account has the donor flag
     * @param playtimeSeconds network-wide play time, which the crest follows from
     */
    public static MessageRef of(
            final DisplayName name,
            final boolean admin,
            final boolean donor,
            final long playtimeSeconds,
            final Prestige prestige) {
        Objects.requireNonNull(name, "name");
        final int tier = prestige.tierOf(playtimeSeconds);
        return DatabaseMessages.MESSAGES
                .player()
                .card(
                        new PlayerContext(name),
                        admin ? Role.ADMIN : donor ? Role.DONOR : Role.PLAYER,
                        new Glyph("crest-" + tier),
                        tier,
                        Duration.ofSeconds(Math.max(0L, playtimeSeconds)));
    }

    /** What a player is on the network, as the card names it; an admin who donated is shown as the admin. */
    public enum Role {
        PLAYER,
        DONOR,
        ADMIN
    }
}
