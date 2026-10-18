package eu.nordtal.season.proxy.playtime;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.proxy.gate.LoginRoster;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Counts the online time of each player, AFK time included, and writes it to {@code player_playtime}. A flush runs on a
 * disconnect and on a timer.
 */
public final class PlaytimeWriter {

    private final PlaytimeStore store;
    private final LoginRoster roster;
    private final Logger logger;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public PlaytimeWriter(final PlaytimeStore store, final LoginRoster roster, final Logger logger, final Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // session lifecycle

    /** Starts counting the time of the player. */
    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        begin(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
    }

    /** Writes the remaining time of the player and deletes the session. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final Player player = event.getPlayer();
        flush(player.getUniqueId());
        forget(player.getUniqueId());
    }

    /** Deletes the session of the player. */
    void forget(final UUID mcUuid) {
        sessions.remove(mcUuid);
    }

    /** Starts a session. A player without a Discord id is not counted. */
    void begin(final UUID mcUuid, final String username) {
        final DiscordId discordId =
                roster.of(mcUuid).map(LoginRoster.Session::discordId).orElse(null);
        if (discordId == null) {
            logger.warn(
                    "Not counting play time for {} ({}): the login path never learned a Discord "
                            + "id for this account",
                    mcUuid,
                    username);
            return;
        }
        sessions.put(mcUuid, new Session(discordId, clock.instant()));
    }

    // flushing

    /**
     * Flushes every session.
     *
     * @return the number of players with written seconds
     */
    public int flushAll() {
        int written = 0;
        for (final UUID mcUuid : sessions.keySet()) {
            if (flush(mcUuid)) {
                written++;
            }
        }
        return written;
    }

    /**
     * Writes the whole seconds since the last flush.
     *
     * @return whether seconds were written
     */
    boolean flush(final UUID mcUuid) {
        final Session session = sessions.get(mcUuid);
        if (session == null) {
            return false;
        }

        synchronized (session) {
            final long seconds =
                    Duration.between(session.since, clock.instant()).toSeconds();
            if (seconds <= 0) {
                return false;
            }

            try {
                store.add(session.discordId, seconds);
            } catch (final RuntimeException exception) {
                logger.warn(
                        "Could not write {}s of play time for {} ({}); it will be written with " + "the next flush",
                        seconds,
                        mcUuid,
                        session.discordId,
                        exception);
                return false;
            }

            session.since = session.since.plusSeconds(seconds);
            return true;
        }
    }

    /** Returns the number of sessions. */
    public int tracked() {
        return sessions.size();
    }

    /** The counting state of a player. */
    private static final class Session {

        private final DiscordId discordId;
        private volatile Instant since;

        private Session(final DiscordId discordId, final Instant since) {
            this.discordId = discordId;
            this.since = since;
        }
    }
}
