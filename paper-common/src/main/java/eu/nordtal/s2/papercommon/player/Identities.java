package eu.nordtal.s2.papercommon.player;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.PlayerIdentity;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Who everybody on this server is, read at pre-login and held until they leave; the one session cache.
 * Render loops read it and never cost a query. {@link #reread} is the one way a held value changes: it reads every
 * held identity in one round trip and tells every watcher of each that differs, so nothing drawn from it goes stale.
 */
public final class Identities {

    private final Function<Collection<PlayerId>, List<PlayerIdentity>> source;
    private final Map<PlayerId, PlayerIdentity> byPlayer = new ConcurrentHashMap<>();
    private final List<Consumer<PlayerIdentity>> watchers = new CopyOnWriteArrayList<>();

    /** @param source reads identities, blocking, leaving out accounts nobody linked: {@code AccessReader#identities} */
    public Identities(final Function<Collection<PlayerId>, List<PlayerIdentity>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Adds a watcher told of every held identity that changed, on the thread that changed it. */
    public void whenChanged(final Consumer<PlayerIdentity> watcher) {
        watchers.add(Objects.requireNonNull(watcher, "watcher"));
    }

    /**
     * Reads a player's identity and holds it; never on the main thread.
     *
     * @throws RuntimeException when the database cannot answer, in which case nothing is held
     */
    public PlayerIdentity load(final PlayerId player) {
        final PlayerIdentity identity =
                source.apply(List.of(player)).stream().findFirst().orElseGet(() -> PlayerIdentity.unknown(player));
        byPlayer.put(player, identity);
        return identity;
    }

    /**
     * Reads every held identity again and changes each that differs; never on the main thread.
     * A player who left meanwhile is not put back, and one nobody linked keeps what is held.
     *
     * @throws RuntimeException when the database cannot answer, in which case nothing changes
     */
    public void reread() {
        final Set<PlayerId> held = Set.copyOf(byPlayer.keySet());
        if (held.isEmpty()) {
            return;
        }
        for (final PlayerIdentity fresh : source.apply(held)) {
            replace(fresh);
        }
    }

    /** Holds the unknown identity for a player whose own could not be read. */
    public void holdUnknown(final PlayerId player) {
        byPlayer.put(player, PlayerIdentity.unknown(player));
    }

    /** Returns what is known about a player now, or the unknown identity while nothing is held. */
    public PlayerIdentity of(final PlayerId player) {
        return byPlayer.getOrDefault(player, PlayerIdentity.unknown(player));
    }

    /** Returns what is known about a Minecraft account now. */
    public PlayerIdentity of(final UUID mcUuid) {
        return of(PlayerId.of(mcUuid));
    }

    /** Returns what is known about an online player now. */
    public PlayerIdentity of(final Player player) {
        return of(player.getUniqueId());
    }

    /** Returns the language a player reads, the network's default while nothing is held. */
    public Locale languageOf(final UUID mcUuid) {
        return of(mcUuid).language();
    }

    /** Returns the linked Discord account of a player held here. */
    public Optional<DiscordId> discordIdOf(final UUID mcUuid) {
        return Optional.ofNullable(of(mcUuid).discordId());
    }

    /** Returns whether a player held here is an admin as of the last read of the roster. */
    public boolean isAdmin(final PlayerId player) {
        return of(player).admin();
    }

    /** Holds a fresh read in place of what is held, telling every watcher when it differs. */
    private void replace(final PlayerIdentity fresh) {
        final PlayerIdentity before = byPlayer.get(fresh.player());
        // replace(): a player who left meanwhile is not put back.
        if (before != null && !fresh.equals(before) && byPlayer.replace(fresh.player(), before, fresh)) {
            watchers.forEach(watcher -> watcher.accept(fresh));
        }
    }

    /** Drops a player who left. */
    public void forget(final PlayerId player) {
        byPlayer.remove(player);
    }

    /** Returns how many players are held. */
    public int size() {
        return byPlayer.size();
    }
}
