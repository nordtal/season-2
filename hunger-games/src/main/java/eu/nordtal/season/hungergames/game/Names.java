package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.access.PlayerIdentity;
import eu.nordtal.season.messages.context.PlayerContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Reads the Minecraft names of players from the identity service. */
public final class Names {

    private final Function<Collection<PlayerId>, List<PlayerIdentity>> identities;

    /** @param identities {@code AccessReader#identities} */
    public Names(final Function<Collection<PlayerId>, List<PlayerIdentity>> identities) {
        this.identities = identities;
    }

    /** Returns the last seen name of each account, without accounts that have none. It blocks. */
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
