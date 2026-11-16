package eu.nordtal.season.proxy.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.access.AccessState;
import eu.nordtal.season.database.access.PlayerCard;
import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.messagerendering.NameCards;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Stores the login data of each connected, linked player.
 * The router, the commands and the play-time writer read it.
 */
public final class LoginRoster {

    /** The login data of a player. Only the admin flag, the language and the country flag change during a session. */
    public record Session(
            DiscordId discordId,
            Locale locale,
            @Nullable String flag,
            boolean admin,
            boolean donor,
            boolean packExempt,
            long playtimeSeconds) {

        Session withAdmin(final boolean admin) {
            return new Session(discordId, locale, flag, admin, donor, packExempt, playtimeSeconds);
        }

        Session withIdentity(final Locale language, final @Nullable String country) {
            return new Session(discordId, language, country, admin, donor, packExempt, playtimeSeconds);
        }
    }

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Stores the session of the state. An unlinked state deletes the session. */
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
                            state.flag(),
                            state.admin(),
                            state.donor(),
                            state.packExempt(),
                            state.playtimeSeconds()));
        } else {
            sessions.remove(mcUuid);
        }
    }

    /** Returns the session of the player, or empty when the player is not connected. */
    public Optional<Session> of(final UUID mcUuid) {
        return mcUuid == null ? Optional.empty() : Optional.ofNullable(sessions.get(mcUuid));
    }

    /** Returns the name card of each connected, linked player. The crest is read when the card is drawn. */
    public NameCards cards(final Supplier<Prestige> prestige) {
        Objects.requireNonNull(prestige, "prestige");
        return name -> of(name.player().value())
                .map(session -> PlayerCard.of(
                        name, session.admin(), session.donor(), session.playtimeSeconds(), prestige.get()))
                .orElse(null);
    }

    /** Returns whether the player is an admin. It is {@code false} for an unknown player. */
    public boolean isAdmin(final UUID mcUuid) {
        return of(mcUuid).map(Session::admin).orElse(Boolean.FALSE);
    }

    /** Returns whether the player is exempt from the resource pack. It is {@code false} for an unknown player. */
    public boolean isPackExempt(final UUID mcUuid) {
        return of(mcUuid).map(Session::packExempt).orElse(Boolean.FALSE);
    }

    /** Returns the language of the player, English for an unknown player. */
    public Locale localeOf(final UUID mcUuid) {
        return of(mcUuid).map(Session::locale).orElse(Locale.ENGLISH);
    }

    /** Returns the pack name of the flag of the player's country, null before a choice or for an unknown player. */
    public @Nullable String flagOf(final UUID mcUuid) {
        return of(mcUuid).map(Session::flag).orElse(null);
    }

    /**
     * Sets the admin flag of every session from the admin Discord ids.
     *
     * @return the number of changed sessions
     */
    public int refreshAdmins(final java.util.Set<String> adminDiscordIds) {
        Objects.requireNonNull(adminDiscordIds, "adminDiscordIds");
        int changed = 0;
        for (final java.util.Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            final Session session = entry.getValue();
            final boolean admin = adminDiscordIds.contains(session.discordId().value());
            if (admin != session.admin()) {
                if (sessions.replace(entry.getKey(), session, session.withAdmin(admin))) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Rereads the language and the country flag of every session, so a change shows without a new login.
     * A read that names another Discord account leaves its session alone.
     *
     * @return the number of changed sessions
     */
    public int refreshIdentities(final Function<UUID, AccessState> read) {
        Objects.requireNonNull(read, "read");
        int changed = 0;
        for (final java.util.Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            final Session session = entry.getValue();
            final AccessState state = read.apply(entry.getKey());
            if (!session.discordId().equals(state.discordId())) {
                continue;
            }
            final Session fresh = session.withIdentity(state.locale(), state.flag());
            if (!fresh.equals(session) && sessions.replace(entry.getKey(), session, fresh)) {
                changed++;
            }
        }
        return changed;
    }

    /** Returns the number of sessions. */
    public int size() {
        return sessions.size();
    }

    /** Deletes the session of a disconnected player. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}
