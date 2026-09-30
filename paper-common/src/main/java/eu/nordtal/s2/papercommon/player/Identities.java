package eu.nordtal.s2.papercommon.player;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.PlayerIdentity;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Who everybody on this server is, read once at pre-login and held until they leave; the one session cache.
 *
 * Render loops read it and never cost a query. A plugin's own per-player data joins it as a {@link Part}.
 */
public final class Identities {

    /** A plugin's own per-player data, loaded and forgotten with the identity it belongs to. */
    public interface Part {

        /** Reads this part for a player who is logging in; blocking, so never on the main thread. */
        void load(PlayerIdentity identity);

        /** Drops what {@link #load} held for a player who left. */
        void forget(PlayerId player);
    }

    private final Function<PlayerId, PlayerIdentity> source;
    private final Map<PlayerId, PlayerIdentity> byPlayer = new ConcurrentHashMap<>();
    private final List<Part> parts = new CopyOnWriteArrayList<>();

    /** @param source reads one identity, blocking; {@code AccessReader#identity} */
    public Identities(final Function<PlayerId, PlayerIdentity> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Adds a part that is loaded and forgotten with every identity from now on. */
    public void attach(final Part part) {
        parts.add(Objects.requireNonNull(part, "part"));
    }

    /**
     * Reads a player's identity and every attached part, and holds them; never on the main thread.
     *
     * @throws RuntimeException when the database cannot answer, in which case nothing is held
     */
    public PlayerIdentity load(final PlayerId player) {
        final PlayerIdentity identity = source.apply(player);
        for (final Part part : parts) {
            part.load(identity);
        }
        byPlayer.put(player, identity);
        return identity;
    }

    /** Holds the unknown identity for a player whose own could not be read. */
    public void holdUnknown(final PlayerId player) {
        byPlayer.put(player, PlayerIdentity.unknown(player));
    }

    /** Returns what is known about a player now, or the unknown identity while nothing is held. */
    public PlayerIdentity of(final PlayerId player) {
        return byPlayer.getOrDefault(player, PlayerIdentity.unknown(player));
    }

    /** Returns what is known about an online player now. */
    public PlayerIdentity of(final Player player) {
        return of(PlayerId.of(player.getUniqueId()));
    }

    /** Returns the linked Discord account of a player held here. */
    public Optional<DiscordId> discordIdOf(final PlayerId player) {
        return Optional.ofNullable(of(player).discordId());
    }

    /** Returns whether a player held here is an admin as of the last read of the roster. */
    public boolean isAdmin(final PlayerId player) {
        return of(player).admin();
    }

    /**
     * Re-derives every held admin flag from the whole roster.
     *
     * @param admins every admin's Minecraft account, freshly read
     * @return whether any held flag changed, so a caller redraws only then
     */
    public boolean recordAdmins(final Set<UUID> admins) {
        boolean changed = false;
        for (final Map.Entry<PlayerId, PlayerIdentity> entry : byPlayer.entrySet()) {
            final boolean admin = admins.contains(entry.getKey().value());
            if (entry.getValue().admin() != admin) {
                byPlayer.computeIfPresent(entry.getKey(), (player, identity) -> identity.withAdmin(admin));
                changed = true;
            }
        }
        return changed;
    }

    /** Drops a player who left, with every attached part. */
    public void forget(final PlayerId player) {
        byPlayer.remove(player);
        for (final Part part : parts) {
            part.forget(player);
        }
    }

    /** Returns how many players are held. */
    public int size() {
        return byPlayer.size();
    }
}
