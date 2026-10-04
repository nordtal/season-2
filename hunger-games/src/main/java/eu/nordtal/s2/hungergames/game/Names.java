package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.PlayerIdentity;
import eu.nordtal.s2.messages.context.PlayerContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** What the players of a game are called, read through the identity service: a Minecraft name, never an account id. */
public final class Names {

    private final Function<Collection<PlayerId>, List<PlayerIdentity>> identities;

    /** @param identities the identity service's read, {@code AccessReader#identities} */
    public Names(final Function<Collection<PlayerId>, List<PlayerIdentity>> identities) {
        this.identities = identities;
    }

    /**
     * Returns these Minecraft accounts by the name each was last seen with; blocking.
     *
     * An account never seen has no name and is left out, which only one that has never played can be.
     */
    public Map<UUID, PlayerContext> of(final Collection<UUID> mcUuids) {
        final Map<UUID, PlayerContext> named = new HashMap<>();
        if (mcUuids.isEmpty()) {
            return named;
        }
        for (final PlayerIdentity identity :
                identities.apply(mcUuids.stream().map(PlayerId::of).toList())) {
            final String name = identity.name();
            if (name != null) {
                named.put(identity.player().value(), PlayerContext.of(identity.player(), name));
            }
        }
        return named;
    }
}
