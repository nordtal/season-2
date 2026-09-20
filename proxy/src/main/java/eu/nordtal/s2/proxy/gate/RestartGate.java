package eu.nordtal.s2.proxy.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The door, shut for the seconds between "this proxy is being moved" and "this proxy has stopped"
 * (season-2-ops/151).
 *
 * <h2>The window this exists for, measured</h2>
 * {@code ProxySwap} parks <b>once</b>, at the moment the countdown reaches zero. What follows is
 * not instant: the worker then waits for the backends to empty, and in run 59 on this host that
 * took sixteen seconds. Whoever connected inside those sixteen seconds was never parked, because
 * parking had already happened, and met the raw Velocity screen when the process went:
 *
 * <pre>
 * 03:20:26  The update moves this proxy: parking 1 player(s) on dev.nordtal.eu:25566 until it is back
 * 03:20:31  [connected player] hmtill has connected
 * 03:20:42  [connected player] hmtill has disconnected: Proxy shutting down.
 * </pre>
 *
 * <h2>Refused rather than transferred, which is Till's call</h2>
 * The other way to close the window is to transfer each arrival onto the standby as they land. It
 * was rejected in the ticket and the reason is the honest one: a refusal with a sentence beats a
 * loading screen that ends in a dropped connection, and a player who is refused knows to come back
 * in a minute. The ten seconds of dead time on the public port while the proxy restarts were
 * already accepted (Till, 2026-09-19) - this makes the few seconds before them look the same from
 * the outside instead of looking like a crash.
 *
 * <h2>Where it does nothing</h2>
 * On the standby, and on a proxy with no {@code network.yml#public-address}: in both cases
 * {@code ProxySwap} never enters the state, so this never fires. The second is a deployment that
 * drops everybody on every update anyway, and a nicer screen for three of them is not worth a
 * second reader of the update row.
 *
 * <p>The locale comes from {@link FallbackCache}, which is memory and not a round trip. A proxy
 * that is seconds from stopping must not open a database connection to pick a language, and the
 * cache holds everybody who logged in recently - which, in a window that opens at the end of a
 * countdown, is very nearly everybody who is trying.</p>
 */
public final class RestartGate {

    private final Logger logger;
    private final BooleanSupplier stopping;
    private final GateMessages messages;
    private final FallbackCache locales;

    /** How many arrivals were turned away, for the log line and for the test. */
    private final AtomicLong refused = new AtomicLong();

    /**
     * @param stopping normally {@code ProxySwap::isStopping} - a supplier rather than the object so
     *                 that the {@code gate} package keeps knowing nothing about {@code update}
     */
    public RestartGate(final Logger logger, final BooleanSupplier stopping,
                       final GateMessages messages, final FallbackCache locales) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stopping = Objects.requireNonNull(stopping, "stopping");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locales = Objects.requireNonNull(locales, "locales");
    }

    /**
     * {@code LoginEvent} and not {@code PreLoginEvent}: the uuid is what the locale cache is keyed
     * on, and it is only settled once the profile is. Nothing here overrides a decision
     * {@link LoginGate} has already made - a player who is refused for a reason of their own keeps
     * that reason's screen, which is the one that tells them something they can act on.
     */
    @Subscribe
    public void onLogin(final LoginEvent event) {
        if (!event.getResult().isAllowed() || !stopping.getAsBoolean()) {
            return;
        }
        event.setResult(ComponentResult.denied(
                refuse(event.getPlayer().getUniqueId(), event.getPlayer().getUsername())));
    }

    /**
     * The decision without the Velocity event around it, so a test can hold it without a
     * {@code Player}.
     *
     * @return the screen they get
     */
    Component refuse(final UUID mcUuid, final String username) {
        final Locale locale = locales.localeOf(mcUuid);
        logger.info("Refused {} ({}): this proxy is being moved by an update and stops in a "
                + "moment, so letting them in would only disconnect them again ({} so far)",
                username, mcUuid, refused.incrementAndGet());
        return messages.restarting(locale);
    }

    /** @return how many arrivals this handler has turned away since the proxy started */
    public long refusedCount() {
        return refused.get();
    }
}
