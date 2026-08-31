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
 * This is not a cache of the access decision - {@link FallbackCache} is that, with its own rules
 * and its own bounded window. This one exists because two things outside the gate need facts the
 * gate reads anyway and must not re-read:
 * </p>
 * <ul>
 *   <li><b>The emergency {@code /phase} command.</b> {@code docs/season-phases.md} says it is
 *       "authorised by {@code discord_user.admin}, the same flag, <b>read with the same query the
 *       login gate already makes</b>". Brigadier calls a command's {@code requires} predicate while
 *       it builds the tree it sends to a client, which is not a place to put a blocking JDBC call;
 *       a map lookup is. It also means an admin who was authorised while the database was up stays
 *       authorised while it is down, which is the situation the emergency path exists for.</li>
 *   <li><b>The play-time writer.</b> {@code player_playtime} is keyed by {@code discord_id}
 *       ({@code V4}), and the proxy learns a player's Discord id exactly once, on the login query.
 *       </li>
 * </ul>
 * <p>
 * <b>Only a linked account is remembered.</b> An unlinked one is refused at the gate and has no
 * Discord id to remember in the first place.
 * </p>
 * <p>
 * Entries die on disconnect and with the process. Nothing here is authoritative about anything: the
 * database is, and every value in here came from it moments earlier.
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
     * entry rather than storing a half-one - the same shape of rule {@link FallbackCache} follows,
     * and for the same reason: an entry nobody could act on correctly is worse than none.
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

    /** @return how many sessions are tracked, for tests and logging */
    public int size() {
        return sessions.size();
    }

    /**
     * Forgets a player on disconnect.
     * <p>
     * Nothing else may depend on this entry surviving the disconnect: handler order between two
     * {@code @Subscribe} methods on the same event is not something to rely on. The play-time
     * writer therefore copies the Discord id out of here when the player <em>joins</em> and keeps
     * its own, rather than reading it back here while it is being removed.
     * </p>
     *
     * @param event the disconnect
     */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
