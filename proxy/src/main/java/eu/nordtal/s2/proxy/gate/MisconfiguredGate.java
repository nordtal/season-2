package eu.nordtal.s2.proxy.gate;

import static eu.nordtal.s2.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Refuses every login because {@code proxy}'s own configuration could not be read.
 *
 * Failing closed is the point: a mistyped key must not silently open the network, so nobody is exempt.
 */
public final class MisconfiguredGate {

    private final Logger logger;
    private final Component screen;
    private final Component motd;

    /** How many logins were refused, so the log says "and 400 others" rather than 400 lines. */
    private final AtomicLong refused = new AtomicLong();

    public MisconfiguredGate(final Logger logger, final MessageRenderer renderer) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(renderer, "renderer");
        this.screen = GateMessages.inEveryLanguage(renderer, MESSAGES.gate().misconfigured());
        // English only: a ping carries no player to take a language from.
        this.motd = renderer.format(Locale.ENGLISH, MESSAGES.motd().misconfigured());
    }

    /** Returns what the server browser shows while nobody can join. */
    Component motd() {
        return motd;
    }

    /** What the server browser shows while nobody can join, never the configured MOTD. */
    @Subscribe
    public void onPing(final ProxyPingEvent event) {
        event.setPing(event.getPing().asBuilder().description(motd).build());
    }

    @Subscribe
    public void onLogin(final LoginEvent event) {
        event.setResult(ComponentResult.denied(
                refuse(event.getPlayer().getUniqueId(), event.getPlayer().getUsername())));
    }

    /**
     * Refuses, counts, and logs the first refusal and every {@value #REPEAT_LOG_EVERY}th after that.
     *
     * @param username their name, for the log line
     */
    Component refuse(final UUID mcUuid, final String username) {
        final long count = refused.incrementAndGet();
        if (count == 1 || count % REPEAT_LOG_EVERY == 0) {
            logger.error(
                    "Refused {} ({}) - proxy is misconfigured, so NOBODY is being "
                            + "let in. Fix the configuration and restart the proxy. ({} refused so far)",
                    mcUuid,
                    username,
                    count);
        }
        return screen;
    }

    /** How many logins this handler has refused. */
    public long refusedCount() {
        return refused.get();
    }

    /** Keeps a busy proxy in this state from writing one error line per join attempt. */
    private static final int REPEAT_LOG_EVERY = 25;
}
