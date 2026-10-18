package eu.nordtal.season.database.access;

import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.value.DisplayName;
import eu.nordtal.season.messages.value.Glyph;
import java.time.Duration;
import java.util.Objects;

/** The card that a player's name shows on hover: role, crest and tier, and play time. */
public final class PlayerCard {

    private PlayerCard() {}

    public static MessageRef of(final DisplayName name, final PlayerIdentity identity, final Prestige prestige) {
        return of(name, identity.admin(), identity.donor(), identity.playtimeSeconds(), prestige);
    }

    /**
     * Returns the card of {@code name}.
     *
     * @param admin whether the account has the admin flag, which wins over the donor flag
     * @param playtimeSeconds the network-wide play time, which the crest follows from
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

    /** What a player is on the network, as the card names it. An admin who donated shows as the admin. */
    public enum Role {
        PLAYER,
        DONOR,
        ADMIN
    }
}
