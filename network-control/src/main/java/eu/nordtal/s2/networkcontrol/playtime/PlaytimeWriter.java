package eu.nordtal.s2.networkcontrol.playtime;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts network-wide online time and writes it to {@code player_playtime}.
 *
 * <p>The proxy counts it because only the proxy sees a session across servers; a backend sees just
 * its own slice. AFK time counts on purpose: prestige measures presence, not effort. The tier is
 * derived from these seconds and never stored, so retuning the thresholds is a config edit rather
 * than a migration.</p>
 *
 * <p>Written on disconnect and periodically in between
 * ({@code gate.yml#playtime-flush-interval-seconds}), so a crash costs minutes rather than a whole
 * session. Both paths run through {@link #flush(UUID)} and the store's single
 * {@code seconds = seconds + N} statement.</p>
 *
 * <p>A flush advances the session's start marker by exactly the whole seconds it wrote, not to
 * "now", so the sub-second remainder is not discarded once per interval.</p>
 *
 * <p>The Discord id that keys the table is copied out of {@link LoginRoster} once, when the player
 * joins, rather than read back at disconnect - the roster is cleared on disconnect too, and handler
 * order for one event is not something to rely on. A player the roster does not know (a login
 * admitted by the fallback cache) is not counted, because there is no key to count them under.</p>
 */
public final class PlaytimeWriter {

    private final PlaytimeStore store;
    private final LoginRoster roster;
    private final Logger logger;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public PlaytimeWriter(final PlaytimeStore store, final LoginRoster roster, final Logger logger) {
        this(store, roster, logger, Clock.systemUTC());
    }

    /** Package-visible so tests can advance time instead of sleeping through it. */
    PlaytimeWriter(final PlaytimeStore store, final LoginRoster roster, final Logger logger,
                   final Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------------------------------------------------------------- session lifecycle

    /**
     * Starts counting. {@code PostLoginEvent} rather than {@code LoginEvent}: the gate may still
     * refuse the login, and a refused player has not been online.
     */
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
     * Stops counting for a player. Always preceded by a {@link #flush(UUID)}, never a substitute
     * for one - dropping a session without writing it is exactly the session loss this class
     * exists to prevent.
     *
     * @param mcUuid the player who has left
     */
    void forget(final UUID mcUuid) {
        sessions.remove(mcUuid);
    }

    /** Package-visible entry point for the two events above, so tests need no Velocity types. */
    void begin(final UUID mcUuid, final String username) {
        final String discordId = roster.of(mcUuid).map(LoginRoster.Session::discordId).orElse(null);
        if (discordId == null) {
            // A login admitted by the fallback cache has no Discord id to key the row by. Logged
            // rather than guessed: a wrong key would corrupt somebody else's total.
            logger.warn("Not counting play time for {} ({}): the login path never learned a Discord "
                    + "id for this account", mcUuid, username);
            return;
        }
        sessions.put(mcUuid, new Session(discordId, clock.instant()));
    }

    // ---------------------------------------------------------------- flushing

    /**
     * One pass over every session being counted. Meant to be called on a fixed schedule.
     *
     * @return how many players had whole seconds written for them
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
     * Writes the whole seconds accumulated since this session's marker and advances the marker by
     * exactly that much.
     *
     * @param mcUuid the player
     * @return whether anything was written
     */
    boolean flush(final UUID mcUuid) {
        final Session session = sessions.get(mcUuid);
        if (session == null) {
            return false;
        }

        // ONE SESSION IS FLUSHED BY ONE THREAD AT A TIME, and the lock has to cover all three
        // steps: reading the marker, writing the seconds, and moving the marker. Otherwise the
        // periodic flushAll and the DisconnectEvent flush - different threads, same player - both
        // read the same `since` and both add the same seconds, crediting the player twice with
        // nothing afterwards to disagree about it.
        //
        // The session and not the whole class: flushAll walks every player, and one slow database
        // write must not hold up the rest.
        synchronized (session) {
            final long seconds = Duration.between(session.since, clock.instant()).toSeconds();
            if (seconds <= 0) {
                return false;
            }

            try {
                store.add(session.discordId, seconds);
            } catch (final RuntimeException exception) {
                // The marker is deliberately NOT advanced: the seconds are still owed and the next
                // flush writes them along with everything since.
                logger.warn("Could not write {}s of play time for {} ({}); it will be written with "
                        + "the next flush", seconds, mcUuid, session.discordId, exception);
                return false;
            }

            // Advance by what was written, not to now, so the sub-second remainder survives.
            session.since = session.since.plusSeconds(seconds);
            return true;
        }
    }

    /** @return how many sessions are being counted, for tests and logging */
    public int tracked() {
        return sessions.size();
    }

    /**
     * One player's running session.
     * <p>
     * Mutable in one field and only from {@link #flush(UUID)}, which holds this object's monitor
     * across the whole read-write-advance. {@code volatile} is not enough on its own: it makes each
     * access atomic and says nothing about two threads doing all three steps at once.
     * </p>
     */
    private static final class Session {

        private final String discordId;
        private volatile Instant since;

        private Session(final String discordId, final Instant since) {
            this.discordId = discordId;
            this.since = since;
        }
    }
}
