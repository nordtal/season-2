package eu.nordtal.season.papercommon.player;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.database.access.PlayerCard;
import eu.nordtal.season.database.access.PlayerIdentity;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.messagerendering.NameCards;
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
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/** The session cache of identities. It reads an identity at pre-login and holds it until the player leaves. */
public final class Identities {

    private final Function<Collection<PlayerId>, List<PlayerIdentity>> source;
    private final Map<PlayerId, PlayerIdentity> byPlayer = new ConcurrentHashMap<>();
    private final List<Consumer<PlayerIdentity>> watchers = new CopyOnWriteArrayList<>();

    public Identities(final Function<Collection<PlayerId>, List<PlayerIdentity>> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Adds a watcher that receives every held identity that changed, on the thread that changed it. */
    public void whenChanged(final Consumer<PlayerIdentity> watcher) {
        watchers.add(Objects.requireNonNull(watcher, "watcher"));
    }

    /** Reads a player's identity and holds it. Never call it on the main thread. */
    public PlayerIdentity load(final PlayerId player) {
        final PlayerIdentity identity =
                source.apply(List.of(player)).stream().findFirst().orElseGet(() -> PlayerIdentity.unknown(player));
        byPlayer.put(player, identity);
        return identity;
    }

    /**
     * Reads every held identity again and replaces each that differs.
     * A player who left is not held again.
     * Never call it on the main thread.
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

    public void holdUnknown(final PlayerId player) {
        byPlayer.put(player, PlayerIdentity.unknown(player));
    }

    public PlayerIdentity of(final PlayerId player) {
        return byPlayer.getOrDefault(player, PlayerIdentity.unknown(player));
    }

    /** Returns the card of each held player. A player who is not held or held as unknown has no card. */
    public NameCards cards(final Supplier<Prestige> prestige) {
        Objects.requireNonNull(prestige, "prestige");
        return name -> held(name.player())
                .filter(identity -> identity.discordId() != null)
                .map(identity -> PlayerCard.of(name, identity, prestige.get()))
                .orElse(null);
    }

    public Optional<PlayerIdentity> held(final PlayerId player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public PlayerIdentity of(final UUID mcUuid) {
        return of(PlayerId.of(mcUuid));
    }

    public PlayerIdentity of(final Player player) {
        return of(player.getUniqueId());
    }

    public Locale languageOf(final UUID mcUuid) {
        return of(mcUuid).language();
    }

    public Optional<DiscordId> discordIdOf(final UUID mcUuid) {
        return Optional.ofNullable(of(mcUuid).discordId());
    }

    public boolean isAdmin(final PlayerId player) {
        return of(player).admin();
    }

    private void replace(final PlayerIdentity fresh) {
        final PlayerIdentity before = byPlayer.get(fresh.player());
        if (before != null && !fresh.equals(before) && byPlayer.replace(fresh.player(), before, fresh)) {
            watchers.forEach(watcher -> watcher.accept(fresh));
        }
    }

    public void forget(final PlayerId player) {
        byPlayer.remove(player);
    }

    public int size() {
        return byPlayer.size();
    }
}
