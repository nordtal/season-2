package eu.nordtal.s2.proxy.playtime;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import eu.nordtal.s2.proxy.gate.LoginRoster;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Counts network-wide online time, AFK included, and writes it to {@code player_playtime}.
 *
 * Flushed on disconnect and on a timer; a player the roster does not know is not counted.
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

    /** Starts counting on {@code PostLoginEvent}, since the gate may still refuse a {@code LoginEvent}. */
    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        begin(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
    }

    /** Writes the last slice of the session and forgets it. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final Player player = event.getPlayer();
        flush(player.getUniqueId());
        forget(player.getUniqueId());
    }

    /**
     * Stops counting for a player, always after a {@link #flush(UUID)}.
     *
     * @param mcUuid the player who has left
     */
    void forget(final UUID mcUuid) {
        sessions.remove(mcUuid);
    }

    /** Entry point for the two events above, so tests need no Velocity types. */
    void begin(final UUID mcUuid, final String username) {
        final String discordId =
                roster.of(mcUuid).map(LoginRoster.Session::discordId).orElse(null);
        if (discordId == null) {
            // No Discord id to key the row by; a guessed key would corrupt a total.
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
     * One pass over every session being counted, meant for a fixed schedule.
     *
     * @return how many players had whole seconds written
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
     * Writes the whole seconds since this session's marker and advances it by exactly that much.
     *
     * @return whether anything was written
     */
    boolean flush(final UUID mcUuid) {
        final Session session = sessions.get(mcUuid);
        if (session == null) {
            return false;
        }

        // Locks the session, not the class: a disconnect flush and flushAll never double-credit.
        synchronized (session) {
            final long seconds =
                    Duration.between(session.since, clock.instant()).toSeconds();
            if (seconds <= 0) {
                return false;
            }

            try {
                store.add(session.discordId, seconds);
            } catch (final RuntimeException exception) {
                // The marker stays put: the seconds are still owed and go out on the next flush.
                logger.warn(
                        "Could not write {}s of play time for {} ({}); it will be written with " + "the next flush",
                        seconds,
                        mcUuid,
                        session.discordId,
                        exception);
                return false;
            }

            // Advance by what was written, not to now, so the sub-second remainder survives.
            session.since = session.since.plusSeconds(seconds);
            return true;
        }
    }

    /** How many sessions are being counted. */
    public int tracked() {
        return sessions.size();
    }

    /** One player's running session; {@link #flush(UUID)} holds its monitor across read, write and advance. */
    private static final class Session {

        private final String discordId;
        private volatile Instant since;

        private Session(final String discordId, final Instant since) {
            this.discordId = discordId;
            this.since = since;
        }
    }
}
