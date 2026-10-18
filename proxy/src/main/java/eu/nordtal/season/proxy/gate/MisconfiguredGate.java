package eu.nordtal.season.proxy.gate;

import static eu.nordtal.season.proxy.ProxyMessages.MESSAGES;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import eu.nordtal.season.messagerendering.MessageRenderer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/** Refuses every login, admins included, when the configuration of the proxy cannot be read. */
public final class MisconfiguredGate {

    private final Logger logger;
    private final Component screen;
    private final Component motd;

    /** The number of refused logins. */
    private final AtomicLong refused = new AtomicLong();

    public MisconfiguredGate(final Logger logger, final MessageRenderer renderer) {
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(renderer, "renderer");
        this.screen = GateMessages.inEveryLanguage(renderer, MESSAGES.gate().misconfigured());
        this.motd = renderer.format(Locale.ENGLISH, MESSAGES.motd().misconfigured());
    }

    /** Returns the MOTD. */
    Component motd() {
        return motd;
    }

    /** Replaces the MOTD of every ping. */
    @Subscribe
    public void onPing(final ProxyPingEvent event) {
        event.setPing(event.getPing().asBuilder().description(motd).build());
    }

    @Subscribe
    public void onLogin(final LoginEvent event) {
        event.setResult(ComponentResult.denied(
                refuse(event.getPlayer().getUniqueId(), event.getPlayer().getUsername())));
    }

    /** Counts the refusal and logs the first refusal and every {@value #REPEAT_LOG_EVERY}th one. */
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

    /** Returns the number of refused logins. */
    public long refusedCount() {
        return refused.get();
    }

    /** Keeps a busy proxy in this state from writing one error line per join attempt. */
    private static final int REPEAT_LOG_EVERY = 25;
}
