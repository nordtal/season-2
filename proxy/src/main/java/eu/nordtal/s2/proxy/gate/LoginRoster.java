package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.access.AccessState;
import eu.nordtal.s2.database.access.PlayerCard;
import eu.nordtal.s2.database.access.Prestige;
import eu.nordtal.s2.messagerendering.NameCards;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What the login query said about each connected, linked player: id, language, flags, exemption and play time.
 *
 * The {@code /phase} requirement and the play-time writer read it instead of querying.
 */
public final class LoginRoster {

    /** One player's login, of which only the admin flag changes while they stay. */
    public record Session(
            DiscordId discordId,
            Locale locale,
            boolean admin,
            boolean donor,
            boolean packExempt,
            long playtimeSeconds) {

        Session withAdmin(final boolean flag) {
            return new Session(discordId, locale, flag, donor, packExempt, playtimeSeconds);
        }
    }

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Records what a successful login query said; an unlinked state removes the entry. */
    public void remember(final UUID mcUuid, final AccessState state) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        Objects.requireNonNull(state, "state");
        if (state.linked()) {
            final DiscordId discordId =
                    Objects.requireNonNull(state.discordId(), "linked() guarantees discordId is set");
            sessions.put(
                    mcUuid,
                    new Session(
                            discordId,
                            state.locale(),
                            state.admin(),
                            state.donor(),
                            state.packExempt(),
                            state.playtimeSeconds()));
        } else {
            sessions.remove(mcUuid);
        }
    }

    /** What the login query said about {@code mcUuid}, if it is still connected. */
    public Optional<Session> of(final UUID mcUuid) {
        return mcUuid == null ? Optional.empty() : Optional.ofNullable(sessions.get(mcUuid));
    }

    /**
     * Returns the card of each player connected here: flags and play time as of their login, the crest as of now.
     * A player not connected through this proxy, or not linked, has none.
     */
    public NameCards cards(final Supplier<Prestige> prestige) {
        Objects.requireNonNull(prestige, "prestige");
        return name -> of(name.player().value())
                .map(session -> PlayerCard.of(
                        name, session.admin(), session.donor(), session.playtimeSeconds(), prestige.get()))
                .orElse(null);
    }

    /** Whether the login query found the admin flag set; {@code false} for anyone unknown. */
    public boolean isAdmin(final UUID mcUuid) {
        return of(mcUuid).map(Session::admin).orElse(Boolean.FALSE);
    }

    /** Returns whether an admin exempted {@code mcUuid} from the resource pack; {@code false} when unknown. */
    public boolean isPackExempt(final UUID mcUuid) {
        return of(mcUuid).map(Session::packExempt).orElse(Boolean.FALSE);
    }

    /** The language {@code mcUuid} logged in with, English when unknown. */
    public Locale localeOf(final UUID mcUuid) {
        return of(mcUuid).map(Session::locale).orElse(Locale.ENGLISH);
    }

    /**
     * Re-derives every session's admin flag from the full set, so a lost notification costs only latency.
     *
     * @return how many sessions changed
     */
    public int refreshAdmins(final java.util.Set<String> adminDiscordIds) {
        Objects.requireNonNull(adminDiscordIds, "adminDiscordIds");
        int changed = 0;
        for (final java.util.Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            final Session session = entry.getValue();
            final boolean admin = adminDiscordIds.contains(session.discordId().value());
            if (admin != session.admin()) {
                // replace(), not put(): a player who disconnected meanwhile must not be put back.
                if (sessions.replace(entry.getKey(), session, session.withAdmin(admin))) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** How many sessions are tracked. */
    public int size() {
        return sessions.size();
    }

    /** Forgets a player on disconnect; handler order is not guaranteed, so nothing may read it afterwards. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
