package eu.nordtal.s2.commands;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.time.NetworkTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks for an irreversible command to be typed again, on a surface that has no buttons.
 *
 * The key is the person and the whole command line, so one command never confirms another.
 */
public final class Confirmations {

    /** How long a pending confirmation stands; not configuration, so it cannot become a delay. */
    public static final Duration WINDOW = Duration.ofSeconds(30);

    private final Duration window;
    private final Clock clock;
    private final Map<String, Instant> pending = new ConcurrentHashMap<>();

    public Confirmations() {
        this(WINDOW, NetworkTime.clock());
    }

    /** Package-visible window and clock, so a test can move time instead of sleeping. */
    Confirmations(final Duration window, final Clock clock) {
        this.window = Objects.requireNonNull(window, "window");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Returns whether this exact command from this person was already asked for, consuming the entry.
     *
     * @param user what was typed, and by whom
     * @param what the full command line, arguments included
     * @return {@code true} when this is the confirmation and the command may run, {@code false} on the first ask
     */
    public boolean confirm(final NordtalUser user, final String what) {
        if (consume(user, what)) {
            return true;
        }
        arm(user, what);
        return false;
    }

    /**
     * Remembers that this command was asked for, for a flow whose confirmation is a different command.
     *
     * {@link #confirm} arms on a miss, so {@code /hg start confirm} typed twice would skip the warning.
     */
    public void arm(final NordtalUser user, final String what) {
        final Instant now = clock.instant();
        sweep(now);
        pending.put(key(user, what), now);
    }

    /** Returns whether this command was armed within the window, consuming it either way and never arming. */
    public boolean consume(final NordtalUser user, final String what) {
        final Instant now = clock.instant();
        sweep(now);

        final Instant asked = pending.remove(key(user, what));
        return asked != null && !asked.plus(window).isBefore(now);
    }

    /** Forgets a pending confirmation, for a cancel or a surface that abandons the flow. */
    public void forget(final NordtalUser user, final String what) {
        pending.remove(key(user, what));
    }

    /** How long a pending confirmation stands, for the sentence that says so. */
    public Duration window() {
        return window;
    }

    /** Returns how many are waiting, for tests. */
    public int size() {
        return pending.size();
    }

    private static String key(final NordtalUser user, final String what) {
        return identityOf(user) + " " + Objects.requireNonNull(what, "what");
    }

    /** Whoever this is, as one string: the Minecraft account first, else the name. */
    private static String identityOf(final NordtalUser user) {
        return user.minecraftUuid()
                .map(UUID::toString)
                .or(() -> user.discordId().map(DiscordId::value))
                .orElseGet(() -> "console:" + user.name());
    }

    /** Drops what has timed out, on every call rather than on a timer. */
    private void sweep(final Instant now) {
        pending.entrySet().removeIf(entry -> entry.getValue().plus(window).isBefore(now));
    }
}
