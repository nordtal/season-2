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
 * <h2>Why the proxy counts it</h2>
 * docs/smp.md#prestige--a-crest-earned-by-time: "Online time is counted <b>network-wide</b>, and
 * <b>{@code network-control} is what counts it</b> - only the proxy sees a session across servers, a
 * backend sees just its own slice." AFK time counts on purpose: prestige measures presence, not
 * effort, which is why play time is not an aura source. The <em>tier</em> is derived from these
 * seconds and never stored, so retuning the thresholds stays a config edit rather than a migration
 * and a backfill; nothing in this class knows about tiers at all.
 *
 * <h2>Written on disconnect and periodically in between</h2>
 * Same source: "It writes the total on disconnect and periodically in between, so a crash costs
 * minutes rather than a whole session." Both paths run through {@link #flush(UUID)} and the store's
 * single {@code seconds = seconds + N} statement, so a periodic flush and the final one cannot
 * disagree about anything.
 * <p>
 * <b>"Periodically" means every five minutes, decided 2026-08-31.</b> docs/smp.md only says
 * "minutes"; the owner picked the number inside that sentence. It stays configuration
 * ({@code gate.yml#playtime-flush-interval-seconds}) rather than a constant, but the default is now
 * a decision rather than a proposal: a proxy crash costs each connected player up to five minutes of
 * counted time, and against a 13-tier crest earned over a whole season that is invisible. See the
 * comment on that key.
 * </p>
 *
 * <h2>No time is lost to rounding</h2>
 * A flush advances the session's start marker by exactly the whole seconds it wrote, not to "now".
 * Flushing on a fixed interval otherwise discards the sub-second remainder each time, which over a
 * long session is real time thrown away for no reason.
 *
 * <h2>The Discord id is captured at join</h2>
 * {@code player_playtime} is keyed by {@code discord_id} ({@code V4}), which the proxy learns from
 * the login query and holds in {@link LoginRoster}. It is copied out once, when the player joins,
 * rather than read back at disconnect: the roster is cleared on disconnect too, and relying on the
 * order of two {@code @Subscribe} handlers for the same event is how a session's last minutes go
 * missing. A player the roster does not know - which means the login went through the fallback
 * cache while the database was down - is simply not counted, because there is no key to count them
 * under.
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
            // The only way to get here is a login admitted by the fallback cache while the database
            // was unreachable, so there is no Discord id to key the row by. Logged rather than
            // guessed: a wrong key would corrupt somebody else's total.
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

        // ONE SESSION IS FLUSHED BY ONE THREAD AT A TIME, and the lock has to cover all three steps:
        // reading the marker, writing the seconds, and moving the marker. Without it the periodic
        // flushAll and the DisconnectEvent flush - which are different threads and can be in here
        // for the same player at the same moment - both read the same `since`, both compute the
        // same seconds, and both hand them to `seconds = seconds + N`. The player is credited twice
        // and both writers then advance the marker, so nothing afterwards disagrees and nothing ever
        // reports it. Prestige is a 13-tier crest earned over a whole season, so the damage is
        // silent and permanent rather than visible.
        //
        // Locking the session and not the whole class is deliberate: flushAll walks every player,
        // and one player's slow database write must not hold up the rest.
        synchronized (session) {
            final long seconds = Duration.between(session.since, clock.instant()).toSeconds();
            if (seconds <= 0) {
                return false;
            }

            try {
                store.add(session.discordId, seconds);
            } catch (final RuntimeException exception) {
                // The marker is deliberately NOT advanced: the seconds are still owed, and the next
                // flush - or the disconnect - writes them along with everything since. A failed
                // flush costs a retry, never the time itself.
                logger.warn("Could not write {}s of play time for {} ({}); it will be written with "
                        + "the next flush", seconds, mcUuid, session.discordId, exception);
                return false;
            }

            // Advance by what was written, not to now, so the sub-second remainder survives to the
            // next flush instead of being thrown away once per interval.
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
     * Mutable in exactly one field and only ever from {@link #flush(UUID)}, which holds this
     * object's monitor across the whole read-write-advance. {@code volatile} alone was not enough
     * and reading as if it were is what the bug looked like: it makes each access atomic and says
     * nothing about two threads doing all three steps at once.
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
