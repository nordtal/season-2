package eu.nordtal.s2.commands;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Type it again" - the confirmation for an irreversible command on a surface that has no buttons.
 *
 * <h2>Why this is here and not in an adapter</h2>
 * Because it holds no platform type at all: a map, a clock and a window. The Paper and Velocity
 * adapters both need it, and Discord needs none of it - a button is its confirmation, and the shape
 * of the two has nothing in common beyond the fact that {@link Declaration#irreversible()} is set.
 * That is why the flag is an obligation on adapters rather than something {@code run} checks: this
 * class is one adapter's way of honouring it.
 *
 * <h2>What is keyed, and why it is the whole command</h2>
 * The identity <em>and</em> the exact command line. Keying on the identity alone would let
 * {@code /phase set MAINTENANCE} confirm a {@code /phase set SMP} typed thirty seconds earlier,
 * which on this particular command is the difference between letting only admins in and
 * disconnecting everybody without access. A confirmation must confirm the thing it was asked about.
 *
 * <h2>The cost on the emergency path, stated because it is real</h2>
 * The proxy's {@code /phase set} is what an admin runs when Discord is down. It used to switch
 * immediately and now takes two invocations, which is a deliberate second or two during an incident;
 * decided by the owner on 2026-09-04, "two-step everywhere". What is bought is that the one command
 * that disconnects every player without active access cannot be run by a mistyped tab completion.
 */
public final class Confirmations {

    /**
     * How long a pending confirmation stands.
     *
     * <p>Long enough to read the sentence and type the command again; short enough that walking away
     * from a keyboard does not leave one armed. Not configuration: nothing else in the repository
     * depends on the number, and a value somebody could set to an hour would quietly turn the whole
     * mechanism into a delay.</p>
     */
    public static final Duration WINDOW = Duration.ofSeconds(30);

    private final Duration window;
    private final Clock clock;
    private final Map<String, Instant> pending = new ConcurrentHashMap<>();

    public Confirmations() {
        this(WINDOW, Clock.systemUTC());
    }

    /** Package-visible window and clock, so a test can move time instead of sleeping. */
    Confirmations(final Duration window, final Clock clock) {
        this.window = Objects.requireNonNull(window, "window");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Ask whether this exact command, from this exact person, was already asked for.
     *
     * <p><b>Consumes.</b> A confirmed command clears its own entry, so running it a third time asks
     * again rather than going straight through - the window is one confirmation wide, not a period
     * during which the command is unguarded.</p>
     *
     * @param user what was typed, and by whom
     * @param what the full command line, arguments included
     * @return {@code true} when this is the confirmation and the command may run; {@code false} when
     *         it is the first ask and the caller should say so and stop
     */
    public boolean confirm(final NordtalUser user, final String what) {
        final String key = key(user, what);
        final Instant now = clock.instant();
        sweep(now);

        final Instant asked = pending.remove(key);
        if (asked != null && !asked.plus(window).isBefore(now)) {
            return true;
        }
        pending.put(key, now);
        return false;
    }

    /** Forget a pending confirmation - for a cancel, or a surface that abandons the flow. */
    public void forget(final NordtalUser user, final String what) {
        pending.remove(key(user, what));
    }

    /** How long a pending confirmation stands, for the sentence that says so. */
    public Duration window() {
        return window;
    }

    /** How many are waiting; for tests, and for a leak nobody expects. */
    public int size() {
        return pending.size();
    }

    private static String key(final NordtalUser user, final String what) {
        return identityOf(user) + " " + Objects.requireNonNull(what, "what");
    }

    /**
     * Whoever this is, as one string.
     *
     * <p>The Minecraft account first, because that is the identity a game surface always has and the
     * one an admin is typing under. The console has neither identity and falls back to its name,
     * which is enough: there is one console per process.</p>
     */
    private static String identityOf(final NordtalUser user) {
        return user.minecraftUuid().map(UUID::toString)
                .or(user::discordId)
                .orElseGet(() -> "console:" + user.name());
    }

    /**
     * Drops what has timed out.
     *
     * <p>On every call rather than on a timer: the map only ever holds admins mid-command, so it is
     * a handful of entries, and a timer would be a thread for a map that is usually empty.</p>
     */
    private void sweep(final Instant now) {
        pending.entrySet().removeIf(entry -> entry.getValue().plus(window).isBefore(now));
    }
}
