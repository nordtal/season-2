package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import eu.nordtal.s2.common.access.AccessState;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the login query said about each connected, linked player: Discord id, language, admin flag and pack exemption.
 *
 * The {@code /phase} requirement and the play-time writer read it instead of querying.
 */
public final class LoginRoster {

    public record Session(String discordId, Locale locale, boolean admin, boolean packExempt) {}

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Records what a successful login query said; an unlinked state removes the entry. */
    public void remember(final UUID mcUuid, final AccessState state) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        Objects.requireNonNull(state, "state");
        if (state.linked()) {
            final String discordId = Objects.requireNonNull(state.discordId(), "linked() guarantees discordId is set");
            sessions.put(mcUuid, new Session(discordId, state.locale(), state.admin(), state.packExempt()));
        } else {
            sessions.remove(mcUuid);
        }
    }

    /** What the login query said about {@code mcUuid}, if it is still connected. */
    public Optional<Session> of(final UUID mcUuid) {
        return mcUuid == null ? Optional.empty() : Optional.ofNullable(sessions.get(mcUuid));
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
            final boolean admin = adminDiscordIds.contains(session.discordId());
            if (admin != session.admin()) {
                // replace(), not put(): a player who disconnected meanwhile must not be put back.
                if (sessions.replace(
                        entry.getKey(),
                        session,
                        new Session(session.discordId(), session.locale(), admin, session.packExempt()))) {
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
