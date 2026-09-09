package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;

import eu.nordtal.s2.common.access.AccessState;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the login query already told us about the players currently connected: their Discord id,
 * their language, and whether they are an admin.
 * <p>
 * Not a cache of the access decision - {@link FallbackCache} is that. This exists because two things
 * outside the gate need facts the gate reads anyway and must not re-read:
 * </p>
 * <ul>
 *   <li><b>The emergency {@code /phase} command.</b> Brigadier calls a command's {@code requires}
 *       predicate while building the tree it sends to a client, which is no place for a blocking
 *       JDBC call. It also keeps an admin authorised while the database is down, which is the
 *       situation the emergency path exists for.</li>
 *   <li><b>The play-time writer.</b> {@code player_playtime} is keyed by {@code discord_id}, and the
 *       proxy learns a player's Discord id exactly once, on the login query.</li>
 * </ul>
 * <p>
 * Only a linked account is remembered. Entries die on disconnect and with the process; the database
 * remains authoritative.
 * </p>
 */
public final class LoginRoster {

    /**
     * @param discordId the linked Discord account, never {@code null} - an unlinked player is not
     *                  in this roster at all
     * @param locale    the player's language as of their login
     * @param admin     the admin flag as of their login
     */
    public record Session(String discordId, Locale locale, boolean admin) {
    }

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Records what a successful login query said. A state that is not linked removes any earlier
     * entry rather than storing a half-one.
     *
     * @param mcUuid the account the query was about
     * @param state  the answer the database just gave
     */
    public void remember(final UUID mcUuid, final AccessState state) {
        Objects.requireNonNull(mcUuid, "mcUuid");
        Objects.requireNonNull(state, "state");
        if (state.linked()) {
            sessions.put(mcUuid, new Session(state.discordId(), state.locale(), state.admin()));
        } else {
            sessions.remove(mcUuid);
        }
    }

    /**
     * @param mcUuid the account
     * @return what the login query said about it, if it is still connected
     */
    public Optional<Session> of(final UUID mcUuid) {
        return mcUuid == null ? Optional.empty() : Optional.ofNullable(sessions.get(mcUuid));
    }

    /**
     * @param mcUuid the account
     * @return whether the login query found the admin flag set; {@code false} for anyone this
     *         roster has never heard of, because refusing an unknown is the safe way round for a
     *         command that switches the phase
     */
    public boolean isAdmin(final UUID mcUuid) {
        return of(mcUuid).map(Session::admin).orElse(Boolean.FALSE);
    }

    /**
     * @param mcUuid the account
     * @return the language it logged in with, English when unknown - a command reply still has to
     *         render for somebody this roster has lost track of
     */
    public Locale localeOf(final UUID mcUuid) {
        return of(mcUuid).map(Session::locale).orElse(Locale.ENGLISH);
    }

    /**
     * Re-derives every tracked session's admin flag from the set the database currently holds, so
     * that an admin who loses the role in Discord loses it in game before they disconnect.
     *
     * <p>Takes the whole set and ignores the id the notification carried: re-deriving every session
     * is idempotent and costs one query either way, which makes a <b>lost</b> notification cost
     * latency rather than correctness. Language is not touched - the next login re-reads it.</p>
     *
     * @param adminDiscordIds every Discord account that currently holds the flag
     * @return how many sessions changed, which is what the caller logs - it is normally zero
     */
    public int refreshAdmins(final java.util.Set<String> adminDiscordIds) {
        Objects.requireNonNull(adminDiscordIds, "adminDiscordIds");
        int changed = 0;
        for (final java.util.Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            final Session session = entry.getValue();
            final boolean admin = adminDiscordIds.contains(session.discordId());
            if (admin != session.admin()) {
                // replace() and not put(): a player who disconnected while this was running must
                // not be put back into the map.
                if (sessions.replace(entry.getKey(),
                        session, new Session(session.discordId(), session.locale(), admin))) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** @return how many sessions are tracked, for tests and logging */
    public int size() {
        return sessions.size();
    }

    /**
     * Forgets a player on disconnect.
     * <p>
     * Nothing may depend on this entry surviving the disconnect: handler order between two
     * {@code @Subscribe} methods on the same event is not something to rely on. The play-time writer
     * therefore copies the Discord id out when the player <em>joins</em>.
     * </p>
     *
     * @param event the disconnect
     */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
